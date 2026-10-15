"""DeepSeek protocol adapter. No tools are executed here."""
from .prompts import messages
from .runtime.policy import LimitReached, ModelFormatError, encoded
from .runtime.state import Action, Understanding


class DeepSeekModel:
    def __init__(self, api_key, limits, base_url="https://api.deepseek.com"):
        from langchain_deepseek import ChatDeepSeek
        self.limits = limits
        self.client = ChatDeepSeek(model="deepseek-flash", api_key=api_key, base_url=base_url,
                                  extra_body={"thinking": {"type": "disabled"}},
                                  disabled_params={"parallel_tool_calls": None}, streaming=False,
                                  disable_streaming=True, max_retries=0,
                                  max_tokens=limits.output_tokens, timeout=60)

    def prepare(self, phase, context):
        schema = Understanding if phase == "understand" else Action
        chat = messages(phase, context)
        structured = self.client.with_structured_output(schema, method="function_calling", strict=False, include_raw=True)
        # Include tools in the bound payload when checking the input limit.
        payload = self.client._get_request_payload(chat, **structured.first.steps__["raw"].kwargs)
        payload.pop("ls_structured_output_format", None)
        if len(encoded(payload).encode()) > self.limits.prompt_bytes:
            raise LimitReached("模型输入超出64KiB限制，请缩小范围")
        return structured, chat

    async def invoke(self, phase, context, config):
        structured, chat = self.prepare(phase, context)
        response = await structured.ainvoke(chat, config=config)
        raw = response["raw"]
        error = None
        if len(raw.tool_calls) != 1 or raw.invalid_tool_calls:
            error = (f"本轮返回了 {len(raw.tool_calls)} 个合法调用和 {len(raw.invalid_tool_calls)} 个非法调用。"
                     "每轮只调用一次结构化函数；只选择下一步的一个动作，其他素材或工具留到下一轮。")
        elif response["parsing_error"]:
            error = str(response["parsing_error"])
        elif raw.response_metadata.get("finish_reason") == "length":
            error = "模型输出已截断，不能当作完整结果"
        return {"value": response["parsed"].model_dump() if error is None and response["parsed"] is not None else None,
                "error": error, "usage": raw.usage_metadata or {},
                "model": raw.response_metadata.get("model_name", "deepseek-flash")}


class ScriptedModel:
    """Explicit offline test model, never labelled as a provider generation."""
    def __init__(self, understanding, actions):
        self.understanding = understanding
        self.actions = actions

    async def invoke(self, phase, context, config):
        if phase == "understand":
            item = self.understanding(context) if callable(self.understanding) else self.understanding
        else:
            index = context["usage"].get("decisions", 0)
            item = self.actions(context) if callable(self.actions) else self.actions[min(index, len(self.actions)-1)]
        if isinstance(item, BaseException):
            raise item
        return {"value": item.model_dump() if hasattr(item, "model_dump") else item,
                "error": None, "usage": {}, "model": "scripted-test-model"}
