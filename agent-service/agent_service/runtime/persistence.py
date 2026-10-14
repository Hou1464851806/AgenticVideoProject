"""Local paid-call receipts. Checkpoints do not roll back remote submissions."""
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
        self.segment = None

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
