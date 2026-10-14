"""Explicit stage-four live verification against already analysed fixed videos.
Creates question scopes and query embeddings, never uploads or reruns ASR/VLM.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_service.config import Config, load_env
from agent_service.model import ScriptedModel
from agent_service.runtime.runner import Agent
from agent_service.tools.java import JavaToolExecutor


class RecordingExecutor(JavaToolExecutor):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.receipts = []

    async def execute(self, request):
        result = await super().execute(request)
        self.receipts.append({"request": request.model_dump(), "result": result.model_dump()})
        return result


async def main(args):
    root = Path(__file__).resolve().parents[2]
    for path in (root / ".env", root / "agent-service/.env.langfuse", root / "agent-service/.env"):
        load_env(path)
    samples = json.loads(args.samples.read_text())["samples"]
    results = []
    for sample in samples:
        if args.deepseek and not args.all_samples and sample["fixture"] not in ("labelled-speech.mp4", "labelled-vision-vfr.mp4"):
            continue
        asset = sample["scope"]["assets"][0]
        executor = RecordingExecutor(args.url, os.environ.get("INTERNAL_WORKER_TOKEN"), sample["scope"]["project_id"], [asset["id"]])
        visual = sample["fixture"] == "labelled-vision-vfr.mp4"
        question = "画面中出现了什么颜色的矩形，以及哪些文字？请引用真实观察时刻，不推断全片覆盖。" if visual else "视频语音中讲了哪些步骤或内容？请引用原文。"
        if args.deepseek and sample["fixture"] == "speech-mention-only.mp4":
            question = "语音是否提到了猫？画面采样是否提供猫出现的证据？请区分来源，稀疏采样不能证明全片绝无猫。"
        def actions(ctx):
            sequence = [
                {"kind": "TOOL", "tool_name": "list_assets", "arguments": {}},
                {"kind": "TOOL", "tool_name": "get_video_overview", "arguments": {"asset_id": asset["id"]}},
                {"kind": "TOOL", "tool_name": "search_evidence", "arguments": {"query": "矩形" if visual else "原文", "source_kinds": ["VISUAL", "VISIBLE_TEXT"] if visual else ["SPEECH"]}},
                {"kind": "TOOL", "tool_name": "read_video_range", "arguments": {"asset_id": asset["id"], "start_ms": 0, "end_ms": asset["duration_ms"]}},
            ]
            index = ctx["usage"]["decisions"]
            if index < len(sequence):
                return sequence[index]
            records = [r for r in ctx["observations"].values() if r["source_kind"] in (["VISUAL", "VISIBLE_TEXT"] if visual else ["SPEECH"])]
            return {"kind": "ANSWER", "content": "；".join(r["text"] for r in records) or "未找到可用来源", "evidence_ids": [r["id"] for r in records]}
        model = None if args.deepseek else ScriptedModel({"relation": "NEW", "mode": "QUERY"}, actions)
        agent = Agent(Config(args.data_dir, trace=not args.no_trace), model=model, executor=executor)
        result = await agent.send(question)
        assert result["status"] == "ANSWER", result
        assert result["outcome"]["mock"] is False
        sources = result["outcome"]["sources"]
        assert sources
        seen = {record["id"]: record for receipt in executor.receipts for record in receipt["result"]["records"]}
        assert all(source == seen[source["id"]] for source in sources), "Final citation must preserve authoritative tool record"
        speech = [source for source in sources if source["source_kind"] == "SPEECH"]
        speech_text = "".join(source["text"] for source in speech)
        if sample["fixture"] == "labelled-speech.mp4":
            assert all(word in speech_text for word in ("张明", "上传", "核查", "导出"))
            assert all(source["start_ms"] == source["audio_start_ms"] + 1436 for source in speech)
        elif sample["fixture"] == "human-speech.mp4":
            assert "告别" in speech_text and "开始" in speech_text
        elif visual:
            assert {source["observed_at_ms"] for source in sources} == {0, 2320, 4760, 7080}
            assert all(source["time_kind"] == "POINT" and "end_ms" not in source for source in sources)
            assert all(word in "".join(source["text"] for source in sources) for word in ("红", "蓝", "证据42", "画面17"))
        else:
            assert "猫" in speech_text
            if args.deepseek:
                assert any(source["source_kind"] == "VISUAL" for source in sources)
        if not args.deepseek:
            assert {r["request"]["tool_name"] for r in executor.receipts} == {"list_assets", "get_video_overview", "search_evidence", "read_video_range"}
        results.append({"fixture": sample["fixture"], "model": "deepseek-flash" if args.deepseek else "scripted-test-model", "result": result, "receipts": executor.receipts})
        args.output.write_text(json.dumps({"date": "2026-10-15", "runs": results}, ensure_ascii=False, indent=2))
        print(json.dumps({"fixture": sample["fixture"], "status": result["status"], "sources": len(result["outcome"]["sources"]), "trace_id": result.get("trace_id")}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, default=Path("docs/verification/java-tools-m1-stage3-live.json"), help="固定样本范围清单；不会从失效范围自动改用最新范围")
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--all-samples", action="store_true")
    parser.add_argument("--deepseek", action="store_true")
    parser.add_argument("--no-trace", action="store_true")
    asyncio.run(main(parser.parse_args()))
