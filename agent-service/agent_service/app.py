"""One execution slot; Spring remains authoritative for attempts and provider receipts."""
import hashlib
import hmac
import json
import os
import shutil
import threading
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

import boto3
import pika
import requests
from fastapi import FastAPI, Header, HTTPException
from botocore.config import Config

from .bailian import BailianAsr, ProviderError, SubmissionUncertain
from .conversation import run_conversation, ConversationUnverified

TOKEN = os.environ.get("INTERNAL_WORKER_TOKEN", "")
BUSINESS = os.environ.get("BUSINESS_URL", "http://business-api:8080")
ROOT = Path(os.environ.get("AGENT_WORK_DIRECTORY", "/work"))
EXECUTOR = str(uuid.uuid4())
active = {}
claims = {}
lock = threading.Lock()
shutdown = threading.Event()


class LostAttempt(RuntimeError):
    pass


class ModelFailed(RuntimeError):
    pass


class Unverified(RuntimeError):
    pass


class BusinessRejected(RuntimeError):
    def __init__(self, error):
        self.code = "BUSINESS_REQUEST_REJECTED"
        message = "业务请求被拒绝，未自动重复提交；请核查请求条件。"
        details = error.get("details", {})
        if error.get("code") == "MODEL_RECORD_TOO_LARGE" and isinstance(details, dict):
            actual, limit, kind = details.get("actualBytes"), details.get("limitBytes"), details.get("recordKind")
            if type(actual) is int and type(limit) is int and actual >= 0 and limit > 0 and kind in {"REQUEST", "RESPONSE"}:
                self.code = "MODEL_RECORD_TOO_LARGE"
                label = "请求" if kind == "REQUEST" else "响应"
                message = f"模型{label}记录为 {actual} 字节，超过配置上限 {limit} 字节；请调整记录上限。"
        super().__init__(message)


def business(path, body, retries=3):
    for attempt in range(retries):
        try:
            response = requests.post(BUSINESS + path, json=body,
                headers={"Authorization": "Bearer " + TOKEN}, timeout=(2, 5))
            if response.status_code in (401, 409):
                raise LostAttempt()
            if response.status_code == 200:
                return response.json()["data"]
            if 400 <= response.status_code < 500 and response.status_code not in (408, 429):
                try:
                    error = response.json().get("error", {})
                except (ValueError, AttributeError):
                    error = {}
                raise BusinessRejected(error if isinstance(error, dict) else {})
        except requests.RequestException:
            pass
        if attempt + 1 < retries:
            time.sleep(.25 * 2 ** attempt)
    raise RuntimeError("Business receipt unavailable")


class Execution:
    def __init__(self, task, reply):
        self.task = task
        self.attempt = reply["attemptId"]
        self.input = reply["input"]
        self.credentials = {"attemptId": self.attempt, "leaseToken": reply["leaseToken"]}
        self.deadline = datetime.fromisoformat(reply["leaseExpiresAt"].replace("Z", "+00:00"))
        self.stop = threading.Event()
        self.cancelled = False
        self.finished = threading.Event()

    def check(self):
        if self.stop.is_set() or datetime.now(timezone.utc) >= self.deadline:
            raise LostAttempt()

    def post(self, action, data):
        return business(f"/internal/v1/tasks/{self.task}/{action}", {**self.credentials, **data})

    def provider(self, action, data=None):
        self.check()
        return self.post("provider-call", {"action": action, "data": data or {}})

    def heartbeat(self):
        while not self.finished.is_set():
            try:
                reply = self.post("heartbeat", {})
                self.deadline = datetime.fromisoformat(reply["leaseExpiresAt"].replace("Z", "+00:00"))
                if reply["action"] == "STOP":
                    self.cancelled = True
                    self.stop.set()
            except LostAttempt:
                self.stop.set()
            except Exception:
                if datetime.now(timezone.utc) >= self.deadline:
                    self.stop.set()
            self.finished.wait(10)

    def run(self):
        directory = ROOT / self.attempt
        heartbeat = threading.Thread(target=self.heartbeat, daemon=True)
        heartbeat.start()
        try:
            if "conversationId" in self.input:
                run_conversation(self, Path(os.environ.get("AGENT_STATE_DIRECTORY", "/state")))
                return
            if not os.environ.get("DASHSCOPE_API_KEY"):
                raise Unverified("未配置百炼凭据，真实语音分析未验证")
            call = self.provider("LOOKUP")
            adapter = BailianAsr(os.environ["DASHSCOPE_API_KEY"],
                os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/api/v1"), self.input["model"])
            if call["status"] == "SUCCEEDED":
                self.post("complete", {"producerKey": "speech-analyzed", "resultType": "SPEECH_ANALYZED", "result": {"modelCallId": call["id"]}})
                return
            provider_id = call.get("providerTaskId")
            if not provider_id:
                if call["status"] != "READY":
                    raise Unverified("上次模型提交结果不明确，不能自动重复付费调用")
                directory.mkdir(parents=True, exist_ok=False)
                audio_path = directory / "audio.wav"
                storage = boto3.client("s3", endpoint_url=os.environ["STORAGE_ENDPOINT"], region_name="us-east-1",
                    aws_access_key_id=os.environ["STORAGE_ACCESS_KEY"], aws_secret_access_key=os.environ["STORAGE_SECRET_KEY"],
                    config=Config(connect_timeout=3, read_timeout=15, retries={"max_attempts": 2}, s3={"addressing_style": "path"}))
                audio = self.input["audio"]
                body = storage.get_object(Bucket=os.environ.get("STORAGE_BUCKET", "originals"), Key=audio["objectKey"])["Body"]
                digest = hashlib.sha256()
                size = 0
                try:
                    with audio_path.open("wb") as destination:
                        for chunk in body.iter_chunks(65536):
                            self.check()
                            size += len(chunk)
                            if size > audio["sizeBytes"]:
                                raise Unverified("派生音频字节数不符合固定输入")
                            digest.update(chunk)
                            destination.write(chunk)
                finally:
                    body.close()
                    storage.close()
                if size != audio["sizeBytes"] or digest.hexdigest() != audio["sha256"]:
                    raise Unverified("派生音频校验失败")
                address = adapter.upload(audio_path)
                self.check()
                call = self.provider("PREPARE")
                if not call["canSubmit"]:
                    raise Unverified("模型提交资格已使用，不能自动重复付费调用")
                try:
                    accepted = adapter.submit(address)
                except SubmissionUncertain:
                    self.provider("UNCERTAIN")
                    raise Unverified("模型提交结果不明确，已停止自动重新提交") from None
                provider_id = accepted["output"]["task_id"]
                call = self.provider("SUBMITTED", {"providerTaskId": provider_id, "requestId": accepted.get("request_id", "")})
            deadline = time.monotonic() + 600
            while True:
                self.check()
                result = adapter.query(provider_id)
                status = result["output"]["task_status"]
                if status == "SUCCEEDED":
                    transcript = adapter.transcript(result)
                    # Keep observations and properties; signed file URLs never enter business evidence.
                    safe = {key: transcript[key] for key in ("properties", "transcripts") if key in transcript}
                    call = self.provider("SUCCEEDED", {"providerTaskId": provider_id, "transcript": safe, "usage": result.get("usage", {})})
                    self.post("complete", {"producerKey": "speech-analyzed", "resultType": "SPEECH_ANALYZED", "result": {"modelCallId": call["id"]}})
                    return
                if status in ("FAILED", "UNKNOWN"):
                    self.provider("FAILED", {"providerTaskId": provider_id, "failure": {"status": status, "code": result["output"].get("code", "UNKNOWN")}, "usage": result.get("usage")})
                    raise ModelFailed("百炼语音任务失败，请核查原任务记录")
                if time.monotonic() >= deadline:
                    raise ProviderError("Provider polling timed out")
                if self.stop.wait(2):
                    raise LostAttempt()
        except LostAttempt:
            pass
        except Exception as failure:
            if "conversationId" in self.input:
                try:
                    self.post("fail", {"producerKey": "conversation-failed", "code": failure.code if isinstance(failure, BusinessRejected) else "CONVERSATION_UNVERIFIED" if isinstance(failure, ConversationUnverified) else "CONVERSATION_FAILED",
                        "message": str(failure) if isinstance(failure, (ConversationUnverified, BusinessRejected)) else "对话执行未完成，未发布正式答复"})
                except Exception:
                    pass
                return
            code = "ASR_PROVIDER_FAILED" if isinstance(failure, ModelFailed) else "ASR_UNVERIFIED" if isinstance(failure, Unverified) else "ANALYSIS_EXECUTION_FAILED"
            message = str(failure) if isinstance(failure, (Unverified, ModelFailed)) else "语音执行暂时失败；保留提供方任务身份后恢复"
            try:
                self.post("fail", {"producerKey": "speech-failed", "code": code, "message": message})
            except Exception:
                pass
        finally:
            self.finished.set()
            heartbeat.join(timeout=16)
            shutil.rmtree(directory, ignore_errors=True)
            if self.cancelled:
                try:
                    self.post("cancelled", {"processExited": True})
                except Exception:
                    pass
            with lock:
                active.pop(self.attempt, None)
                claims.pop(self.task, None)


def consume():
    while not shutdown.is_set():
        try:
            credentials = pika.PlainCredentials(os.environ["RABBITMQ_USER"], os.environ["RABBITMQ_PASSWORD"])
            connection = pika.BlockingConnection(pika.ConnectionParameters(os.environ.get("RABBITMQ_HOST", "rabbitmq"), credentials=credentials,
                heartbeat=30, blocked_connection_timeout=10, socket_timeout=5))
            channel = connection.channel()
            channel.queue_declare("analysis.v1", durable=True, arguments={"x-dead-letter-exchange": "", "x-dead-letter-routing-key": "analysis.dead.v1"})
            while not shutdown.is_set():
                connection.process_data_events(time_limit=.3)
                with lock:
                    busy = bool(active)
                if busy:
                    continue
                method, _, body = channel.basic_get("analysis.v1", auto_ack=False)
                if not method:
                    continue
                task = json.loads(body)["taskId"]
                claim = claims.setdefault(task, str(uuid.uuid4()))
                try:
                    reply = business(f"/internal/v1/tasks/{task}/claim", {"executorId": EXECUTOR, "claimRequestId": claim})
                except LostAttempt:
                    channel.basic_ack(method.delivery_tag)
                    claims.pop(task, None)
                    continue
                if not reply.get("claimed"):
                    channel.basic_ack(method.delivery_tag)
                    claims.pop(task, None)
                    continue
                execution = Execution(task, reply)
                with lock:
                    active[execution.attempt] = execution
                channel.basic_ack(method.delivery_tag)
                threading.Thread(target=execution.run, daemon=True).start()
            connection.close()
        except Exception:
            shutdown.wait(2)


@asynccontextmanager
async def lifespan(app):
    ROOT.mkdir(parents=True, exist_ok=True)
    for entry in ROOT.iterdir():
        try:
            uuid.UUID(entry.name)
        except ValueError:
            continue
        if entry.is_dir() and not entry.is_symlink():
            shutil.rmtree(entry)
    consumer = threading.Thread(target=consume, daemon=True)
    consumer.start()
    yield
    shutdown.set()
    with lock:
        for execution in active.values():
            execution.stop.set()
    consumer.join(timeout=10)


app = FastAPI(lifespan=lifespan)


def authorize(authorization):
    if not TOKEN or not hmac.compare_digest(authorization or "", "Bearer " + TOKEN):
        raise HTTPException(401, "Internal credentials required")


@app.get("/health")
def health():
    return {"status": "UP"}


@app.get("/internal/v1/executions/{attempt}")
def state(attempt: uuid.UUID, authorization: str | None = Header(default=None)):
    authorize(authorization)
    with lock:
        execution = active.get(str(attempt))
        return {"executorId": EXECUTOR, "state": "NOT_FOUND" if execution is None else "STOPPING" if execution.stop.is_set() else "RUNNING"}


@app.post("/internal/v1/executions/{attempt}/stop")
def stop(attempt: uuid.UUID, authorization: str | None = Header(default=None)):
    authorize(authorization)
    with lock:
        execution = active.get(str(attempt))
        if execution:
            execution.stop.set()
    return state(attempt, authorization)
