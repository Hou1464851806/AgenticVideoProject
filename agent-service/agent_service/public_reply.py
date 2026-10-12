"""Whitelist final-answer text; never forward assistant reasoning or raw tool fields."""
import json
import time
import uuid


class PublicReply:
    def __init__(self, execution):
        self.execution = execution
        self.sequence = 0
        self.message = str(uuid.uuid4())
        self.version = 0
        self.text = ""
        self.flushed_at = 0

    def event(self, kind, payload):
        self.execution.check()
        self.sequence += 1
        self.execution.post("events", {"producerSeq": self.sequence, "type": kind, "payload": payload})

    def phase(self, phase):
        self.event("activity.updated", {"phase": phase})

    def update(self, text, force=False):
        if not isinstance(text, str) or not text.startswith(self.text) or len(text) > 8000:
            return
        if not force and len(text) - len(self.text) < 128 and time.monotonic() - self.flushed_at < .25:
            return
        while len(text) > len(self.text):
            append = text[len(self.text):len(self.text) + 2048]
            self.event("assistant.delta", {"messageId": self.message, "baseContentVersion": self.version,
                       "offset": len(self.text), "appendText": append})
            self.version += 1
            self.text += append
            self.flushed_at = time.monotonic()


def answer_prefix(arguments):
    """Decode only the top-level content string, stopping before incomplete escapes."""
    decoder = json.JSONDecoder()
    index = 0
    try:
        index = len(arguments) - len(arguments.lstrip())
        if arguments[index] != "{":
            return ""
        index += 1
        while True:
            while arguments[index].isspace():
                index += 1
            key, index = decoder.raw_decode(arguments, index)
            while arguments[index].isspace():
                index += 1
            if arguments[index] != ":":
                return ""
            index += 1
            while arguments[index].isspace():
                index += 1
            if key == "content":
                if arguments[index] != '"':
                    return ""
                index += 1
                result = []
                escapes = {'"':'"', '\\':'\\', '/':'/', 'b':'\b', 'f':'\f', 'n':'\n', 'r':'\r', 't':'\t'}
                while index < len(arguments):
                    char = arguments[index]
                    if char == '"':
                        break
                    if char == '\\':
                        if index + 1 >= len(arguments):
                            break
                        escape = arguments[index + 1]
                        if escape == 'u':
                            if index + 6 > len(arguments):
                                break
                            point = int(arguments[index + 2:index + 6], 16)
                            if 0xD800 <= point <= 0xDBFF:
                                if index + 12 > len(arguments):
                                    break
                                if arguments[index + 6:index + 8] != '\\u':
                                    return ""
                                low = int(arguments[index + 8:index + 12], 16)
                                if not 0xDC00 <= low <= 0xDFFF:
                                    return ""
                                result.append(chr(0x10000 + (point - 0xD800) * 1024 + low - 0xDC00)); index += 12; continue
                            if 0xDC00 <= point <= 0xDFFF:
                                return ""
                            result.append(chr(point)); index += 6; continue
                        if escape not in escapes:
                            return ""
                        result.append(escapes[escape]); index += 2; continue
                    if ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF:
                        return ""
                    result.append(char); index += 1
                return ''.join(result)
            _, index = decoder.raw_decode(arguments, index)
            while arguments[index].isspace():
                index += 1
            if arguments[index] != ',':
                return ""
            index += 1
    except (ValueError, IndexError):
        return ""


async def streamed_response(reply, execution, public):
    # Legacy protocol fixtures remain useful for business size-limit verification.
    if "text/event-stream" not in reply.headers.get("Content-Type", ""):
        await reply.aread()
        return reply.json()
    calls, message, usage = {}, {"role": "assistant", "content": ""}, {}
    identity = model = None
    finished = False
    frame = []
    async for line in reply.aiter_lines():
        execution.check()
        if line:
            if line.startswith("data:"):
                frame.append(line[5:].lstrip())
            continue
        if not frame:
            continue
        data = '\n'.join(frame); frame = []
        if data == "[DONE]":
            finished = True
            break
        chunk = json.loads(data)
        identity, model = chunk.get("id", identity), chunk.get("model", model)
        usage = chunk.get("usage") or usage
        choices = chunk.get("choices", [])
        if not choices:
            continue
        delta = choices[0].get("delta", {})
        for field in ("content", "reasoning_content"):
            if isinstance(delta.get(field), str):
                message[field] = message.get(field, "") + delta[field]
        for part in delta.get("tool_calls") or []:
            index = part["index"]
            if not isinstance(index, int) or index < 0 or index >= 12:
                raise ValueError("Too many tool calls")
            call = calls.setdefault(index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
            if part.get("id"):
                call["id"] = part["id"]
            function = part.get("function", {})
            for field in ("name", "arguments"):
                if isinstance(function.get(field), str):
                    call["function"][field] += function[field]
            # Only the designated public-answer argument is decoded for provisional display.
            if index == 0 and call["function"]["name"] == "answer_query":
                public.update(answer_prefix(call["function"]["arguments"]))
    if not finished:
        raise ValueError("Provider stream ended without a completion receipt")
    message["tool_calls"] = [calls[index] for index in sorted(calls)]
    if calls.get(0, {}).get("function", {}).get("name") == "answer_query":
        public.update(answer_prefix(calls[0]["function"]["arguments"]), force=True)
    return {"id": identity, "model": model, "choices": [{"message": message}], "usage": usage}
