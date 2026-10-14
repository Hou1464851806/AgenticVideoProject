from typing import Protocol
from .contracts import ToolRequest, ToolResult


class ToolExecutor(Protocol):
    async def execute(self, request: ToolRequest) -> ToolResult: ...
