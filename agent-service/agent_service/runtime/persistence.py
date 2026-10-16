"""Local paid-call receipts. Checkpoints do not roll back remote submissions."""
import json
import sqlite3
import time
from pathlib import Path

from .policy import Cancelled, LimitReached, SubmissionUnknown, encoded


class Store:
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path, timeout=10, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS executions (
            id TEXT PRIMARY KEY, calls INTEGER NOT NULL DEFAULT 0,
            seconds REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'IDLE');
          CREATE TABLE IF NOT EXISTS model_calls (
            id TEXT PRIMARY KEY, execution_id TEXT NOT NULL, input_hash TEXT NOT NULL,
            status TEXT NOT NULL, response TEXT);
        """)
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS conversation_runs (
            task_id TEXT PRIMARY KEY, input_json TEXT NOT NULL,
            binding_json TEXT, state_json TEXT, prepared_json TEXT, result_json TEXT);
          CREATE TABLE IF NOT EXISTS execution_transfers (
            source_id TEXT PRIMARY KEY, target_id TEXT NOT NULL);
        """)
        self.segment = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def accept_run(self, value):
        task_id = value["identity"]["taskId"]
        fixed = encoded(value)
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO conversation_runs(task_id,input_json) VALUES (?,?)", (task_id, fixed))
            row = self.db.execute("SELECT input_json,binding_json,state_json,prepared_json,result_json FROM conversation_runs WHERE task_id=?", (task_id,)).fetchone()
            if row[0] != fixed:
                raise ValueError("同一接纳任务的固定输入发生变化")
        return {key: json.loads(data) if data else None for key, data in
                zip(("binding", "initial", "prepared", "result"), row[1:])}

    def record_run(self, task_id, *, prepared=None, result=None):
        with self.db:
            self.db.execute("UPDATE conversation_runs SET prepared_json=COALESCE(prepared_json,?), result_json=COALESCE(result_json,?) WHERE task_id=?",
                            (encoded(prepared) if prepared else None, encoded(result) if result else None, task_id))

    def bind_run(self, task_id, binding, state):
        with self.db:
            self.db.execute("UPDATE conversation_runs SET binding_json=?,state_json=? WHERE task_id=? AND binding_json IS NULL",
                            (encoded(binding), encoded(state), task_id))
            row = self.db.execute("SELECT binding_json,state_json FROM conversation_runs WHERE task_id=?", (task_id,)).fetchone()
            if row[0] != encoded(binding):
                raise ValueError("同一接纳任务的执行绑定发生变化")
        return json.loads(row[1])

    def transfer_usage(self, source_id, target_id):
        """Charge entry understanding once, retaining its original paid-call receipts."""
        if source_id == target_id:
            return
        self.db.execute("BEGIN IMMEDIATE")
        try:
            row = self.db.execute("SELECT target_id FROM execution_transfers WHERE source_id=?", (source_id,)).fetchone()
            if row:
                if row[0] != target_id:
                    raise ValueError("理解预算已归入另一执行")
            else:
                source = self.db.execute("SELECT calls,seconds FROM executions WHERE id=?", (source_id,)).fetchone()
                self.db.execute("INSERT OR IGNORE INTO executions(id) VALUES (?)", (target_id,))
                self.db.execute("UPDATE executions SET calls=calls+?,seconds=seconds+? WHERE id=?", (*source, target_id))
                self.db.execute("INSERT INTO execution_transfers VALUES (?,?)", (source_id, target_id))
            self.db.commit()
        except BaseException:
            self.db.rollback()
            raise

    def start(self, execution_id):
        self.db.execute("INSERT OR IGNORE INTO executions(id) VALUES (?)", (execution_id,))
        status = self.db.execute("SELECT status FROM executions WHERE id=?", (execution_id,)).fetchone()[0]
        if status == "CANCELLED":
            raise Cancelled("执行已取消")
        self.db.execute("UPDATE executions SET status='RUNNING' WHERE id=?", (execution_id,))
        self.db.commit()
        self.segment = (execution_id, time.monotonic())

    def usage(self, execution_id):
        row = self.db.execute("SELECT calls,seconds,status FROM executions WHERE id=?", (execution_id,)).fetchone()
        extra = time.monotonic() - self.segment[1] if self.segment and self.segment[0] == execution_id else 0
        return {"model_calls": row[0], "active_seconds": row[1] + extra, "status": row[2]}

    def check(self, execution_id, limits):
        if self.segment and self.segment[0] == execution_id:
            now = time.monotonic()
            self.db.execute("UPDATE executions SET seconds=seconds+? WHERE id=?", (now-self.segment[1], execution_id))
            self.db.commit()
            self.segment = (execution_id, now)
        usage = self.usage(execution_id)
        if usage["status"] == "CANCELLED":
            raise Cancelled("执行已取消")
        if usage["active_seconds"] >= limits.active_seconds:
            raise LimitReached("有效执行耗时额度已用尽")

    def reserve(self, call_id, execution_id, input_hash, limits):
        import json
        self.check(execution_id, limits)
        self.db.execute("BEGIN IMMEDIATE")
        try:
            row = self.db.execute("SELECT input_hash,status,response FROM model_calls WHERE id=?", (call_id,)).fetchone()
            if row:
                if row[0] != input_hash:
                    raise RuntimeError("同一模型调用身份的输入发生变化")
                if row[1] == "RECEIVED":
                    self.db.commit()
                    return json.loads(row[2])
                raise SubmissionUnknown("上次模型提交回执不确定，已停止自动重付")
            calls = self.db.execute("SELECT calls FROM executions WHERE id=?", (execution_id,)).fetchone()[0]
            if calls >= limits.model_calls:
                raise LimitReached("模型请求额度已用尽")
            self.db.execute("INSERT INTO model_calls VALUES (?,?,?,'SUBMITTING',NULL)", (call_id, execution_id, input_hash))
            self.db.execute("UPDATE executions SET calls=calls+1 WHERE id=?", (execution_id,))
            self.db.commit()
            return None
        except BaseException:
            self.db.rollback()
            raise

    def received(self, call_id, response):
        self.db.execute("UPDATE model_calls SET status='RECEIVED',response=? WHERE id=? AND status='SUBMITTING'", (encoded(response), call_id))
        self.db.commit()

    def unknown(self, call_id):
        self.db.execute("UPDATE model_calls SET status='UNKNOWN' WHERE id=? AND status='SUBMITTING'", (call_id,))
        self.db.commit()

    def cancel(self, execution_id):
        self.db.execute("UPDATE executions SET status='CANCELLED' WHERE id=?", (execution_id,))
        self.db.commit()

    def finish(self, status):
        if self.segment:
            execution_id, started = self.segment
            self.db.execute("UPDATE executions SET seconds=seconds+?,status=CASE WHEN status='CANCELLED' THEN status ELSE ? END WHERE id=?", (time.monotonic()-started, status, execution_id))
            self.db.commit()
            self.segment = None

    def close(self):
        self.db.close()
