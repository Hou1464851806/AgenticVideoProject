"""Optional local observability. Never owns execution state or changes routing."""
import contextlib
import os
import sys
from urllib.parse import urlparse


def mask(data, **kwargs):
    if isinstance(data, dict):
        return {key: ("[redacted]" if any(word in key.lower() for word in ("api_key", "secret", "authorization", "reasoning_content")) else mask(value)) for key, value in data.items()}
    if isinstance(data, list):
        return [mask(item) for item in data[:100]]
    if isinstance(data, str):
        return data[:32768]
    return data


class Tracer:
    def __init__(self, enabled=True):
        self.client, self.callbacks, self.current_tool = None, [], None
        self.root = None
        self.trace_id = None
        if not enabled:
            return
        public, secret = os.environ.get("LANGFUSE_PUBLIC_KEY"), os.environ.get("LANGFUSE_SECRET_KEY")
        base = os.environ.get("LANGFUSE_BASE_URL", "http://localhost:3035")
        if not public or not secret:
            print("观测未启用：缺少本机 Langfuse 配置。", file=sys.stderr)
            return
        if urlparse(base).hostname not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("This MVP requires local self-hosted Langfuse")
        try:
            import httpx
            httpx.get(base+"/api/public/health", timeout=2).raise_for_status()
            from langfuse import Langfuse
            from langfuse.langchain import CallbackHandler
            self.client = Langfuse(public_key=public, secret_key=secret, base_url=base, timeout=3,
                                   environment="local-agent-mvp", mask=mask)
            self.callbacks = [CallbackHandler(public_key=public)]
        except Exception as error:
            print(f"本机 Langfuse 暂不可用（{type(error).__name__}），Agent 将继续，本次不计入观测验收。", file=sys.stderr)

    @contextlib.contextmanager
    def segment(self, identity):
        if not self.client:
            yield
            return
        from langfuse import propagate_attributes
        with propagate_attributes(session_id=identity["session_id"], metadata=identity):
            with self.client.start_as_current_observation(name="agent-invocation", as_type="agent", metadata=identity) as root:
                self.trace_id = root.trace_id
                self.root = root
                yield

    def result(self, result):
        if self.root:
            self.root.update(output=result, metadata={"final_status": result["status"], "prompt_version": "mvp-1"})

    @contextlib.contextmanager
    def tool(self, request):
        if not self.client:
            yield
            return
        with self.client.start_as_current_observation(name=request.tool_name, as_type="tool",
                                                      input=request.model_dump(), metadata={"mock": True}) as span:
            self.current_tool = span
            try:
                yield
            finally:
                self.current_tool = None

    def tool_result(self, result):
        if self.current_tool:
            self.current_tool.update(output=result.model_dump(), level="ERROR" if result.status == "ERROR" else "DEFAULT")

    def reused(self, call_id):
        if self.client:
            with self.client.start_as_current_observation(name="model-response-reused", metadata={"call_id": call_id, "reused": True}):
                pass

    async def flush(self):
        if self.client:
            import asyncio
            try:
                await asyncio.wait_for(asyncio.to_thread(self.client.flush), timeout=5)
            except Exception as error:
                print(f"Langfuse flush 未完成（{type(error).__name__}）。", file=sys.stderr)
