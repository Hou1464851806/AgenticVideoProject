"""Scope-bound Java reads. Credentials and HTTP details never enter graph state."""
import httpx

from .contracts import ToolRequest, ToolResult
from .catalog import READ_TOOLS


class JavaToolExecutor:
    mock = False

    def __init__(self, base_url, token, project_id=None, asset_ids=None, *, transport=None):
        if not token:
            raise ValueError("真实模式需要 INTERNAL_WORKER_TOKEN")
        self.base_url = base_url.rstrip("/") + "/internal/v1/questions"
        self._token = token
        self.project_id, self.asset_ids = project_id, asset_ids
        self.scope_id = None
        self.transport = transport

    async def _request(self, method, path, body=None):
        async with httpx.AsyncClient(transport=self.transport, timeout=60, follow_redirects=False) as client:
            return await client.request(method, self.base_url + path, json=body,
                                        headers={"Authorization": "Bearer " + self._token})

    async def snapshot(self, saved=None):
        if saved and self.project_id and self.project_id != saved["project_id"]:
            raise ValueError("恢复配置项目与原快照不匹配")
        if saved:
            response = await self._request("GET", "/scopes/" + saved["scope_id"])
        else:
            if not self.project_id:
                raise ValueError("真实模式首次运行需要 --project")
            response = await self._request("POST", "/scopes", {"project_id": self.project_id, "asset_ids": self.asset_ids})
        if response.status_code not in (200, 201):
            raise ValueError(f"问答快照不可用（HTTP {response.status_code}），请核查原范围和依赖")
        scope = response.json()
        if scope.get("mock") is not False or not isinstance(scope.get("assets"), list):
            raise ValueError("Java 返回了无效问答快照")
        if saved and scope != saved:
            raise ValueError("原问答快照内容发生变化，停止恢复")
        self.scope_id = scope["scope_id"]
        return scope

    async def execute(self, request: ToolRequest) -> ToolResult:
        def error(code, category):
            return ToolResult(**{k: getattr(request, k) for k in ("call_id", "tool_name", "request_hash")},
                              status="ERROR", mock=False, error={"code": code, "category": category})
        if request.tool_name not in READ_TOOLS:
            return error("CAPABILITY_UNAVAILABLE", "CAPABILITY_UNAVAILABLE")
        if not self.scope_id:
            raise ValueError("Java 工具尚未绑定问答快照")
        try:
            response = await self._request("POST", "/scopes/" + self.scope_id + "/tools", request.model_dump())
        except httpx.TransportError:
            # A query embedding may have been submitted. Never automatically resubmit.
            return error("TOOL_TRANSPORT_UNCERTAIN", "EXECUTION_UNCERTAIN")
        if response.status_code != 200:
            if response.status_code in (401, 403):
                return error("TOOL_ACCESS_DENIED", "PERMISSION_DENIED")
            try:
                failure = response.json().get("error", {})
                if isinstance(failure, dict) and isinstance(failure.get("code"), str) and isinstance(failure.get("category"), str):
                    return error(failure["code"], failure["category"])
            except (ValueError, AttributeError):
                pass
            return error("TOOL_HTTP_" + str(response.status_code), "DEPENDENCY_UNAVAILABLE")
        try:
            result = ToolResult.model_validate(response.json())
        except (ValueError, TypeError):
            return error("TOOL_PROTOCOL_INVALID", "EXECUTION_UNCERTAIN")
        if result.mock or result.status == "PENDING":
            return error("TOOL_PROTOCOL_INVALID", "EXECUTION_UNCERTAIN")
        if (result.call_id, result.tool_name, result.request_hash) != (request.call_id, request.tool_name, request.request_hash):
            return error("TOOL_IDENTITY_MISMATCH", "EXECUTION_UNCERTAIN")
        return result
