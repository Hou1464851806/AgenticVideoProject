"""Human-facing local CLI and JSON result interface."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import sys

from .config import Config, load_env
from .model import ScriptedModel
from .preview import PreviewClient, PreviewClientError
from .runtime.runner import Agent
from .tools.contracts import ToolRequest
from .tools.java import JavaToolExecutor
from .tools.mock import SCENARIOS


def parser():
    cli = argparse.ArgumentParser(description="视频 Agent：Mock 离线或 Java 真实问答／草稿制作")
    cli.add_argument("--java-url", help="启用真实模式，例如 http://localhost:8080")
    cli.add_argument("--project", help="首次真实问答／制作的项目 UUID")
    cli.add_argument("--asset", action="append", help="可重复指定初始素材 UUID")
    cli.add_argument("--data-dir", type=Path, default=Config().directory)
    cli.add_argument("--scenario", choices=sorted(SCENARIOS), default="normal")
    cli.add_argument("--script", type=Path, help="显式离线脚本模型；不验证真实模型能力")
    cli.add_argument("--no-trace", action="store_true")
    commands = cli.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run")
    run.add_argument("message")
    run.add_argument("--thread")
    run.add_argument("--new", action="store_true")
    for name in ("status", "resume", "cancel", "save", "complete-mock"):
        commands.add_parser(name).add_argument("thread")
    reply = commands.add_parser("reply")
    reply.add_argument("thread")
    reply.add_argument("content")
    reply.add_argument("--wait-id")
    result = commands.add_parser("tool-result")
    result.add_argument("thread")
    result.add_argument("file", type=Path)
    result.add_argument("--wait-id")
    preview = commands.add_parser("preview")
    preview.add_argument("thread")
    preview.add_argument("--output", help="保存结果中的 output_key；多输出时必填")
    commands.add_parser("preview-list").add_argument("thread")
    for name in ("preview-status", "preview-cancel", "preview-retry"):
        commands.add_parser(name).add_argument("request_id")
    return cli


async def preview_dispatch(args):
    client = PreviewClient(args.java_url, os.environ.get("INTERNAL_WORKER_TOKEN"))
    if args.command == "preview-status":
        return (await client.status(args.request_id)).model_dump()
    if args.command == "preview-cancel":
        return (await client.cancel(args.request_id)).model_dump()
    if args.command == "preview-retry":
        return (await client.retry(args.request_id)).model_dump()
    state = await Agent(Config(args.data_dir, trace=False)).inspect(args.thread)
    production = state["production"]
    if not production:
        raise ValueError("该 thread 没有真实制作身份，不能预览")
    if not state["saved_result"]:
        if not state["save_request"] or await client.saved(production["requirement_id"]) is None:
            raise ValueError("请先完成显式保存，再请求预览")
    if args.command == "preview-list":
        return (await client.list(production["requirement_id"])).model_dump()
    return (await client.submit(production["requirement_id"], args.output)).model_dump()


async def dispatch(args):
    root = Path(__file__).resolve().parents[1]
    load_env(root.parent / ".env")
    load_env(root / ".env.langfuse")
    load_env(root / ".env")
    if args.command.startswith("preview"):
        return await preview_dispatch(args)
    model = None
    if args.script:
        script = json.loads(args.script.read_text())
        def understand(ctx):
            return script.get("after_clarification", script["understanding"]) if ctx["request"].get("clarification") else script["understanding"]
        model = ScriptedModel(understand, script["actions"])
    if args.java_url and args.command in ("complete-mock", "tool-result"):
        raise ValueError("真实模式不接收外部工具完成事件")
    executor = JavaToolExecutor(args.java_url, os.environ.get("INTERNAL_WORKER_TOKEN"), args.project, args.asset) if args.java_url else None
    agent = Agent(Config(args.data_dir, scenario=args.scenario, trace=not args.no_trace), model=model,
                  executor=executor, progress=lambda node: print(f"[{node}]", file=sys.stderr))
    if args.command == "run":
        return await agent.send(args.message, thread_id=args.thread, new=args.new)
    if args.command == "save":
        return await agent.save(args.thread)
    if args.command == "status":
        return await agent.inspect(args.thread)
    if args.command == "cancel":
        return await agent.cancel(args.thread)
    if args.command == "resume":
        return await agent.resume(args.thread)
    state = await agent.inspect(args.thread)
    waiting = state["waiting"]
    if not waiting:
        raise ValueError("该需求没有等待输入")
    if args.command == "reply":
        value = {"kind": "USER_INPUT", "wait_id": args.wait_id or waiting["wait_id"], "content": args.content}
    else:
        result = agent.executor.complete(ToolRequest.model_validate(state["pending_tool"])).model_dump() if args.command == "complete-mock" else json.loads(args.file.read_text())
        value = {"kind": "TOOL_RESULT", "wait_id": getattr(args, "wait_id", None) or waiting["wait_id"], "result": result}
    return await agent.resume(args.thread, value)


def main():
    args = parser().parse_args()
    try:
        result = asyncio.run(dispatch(args))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if args.command.startswith("preview"):
            if args.command == "preview-list":
                return 0
            if args.command == "preview-cancel":
                print("取消作用于实际任务，会影响复用该任务的所有请求；CANCEL_REQUESTED 表示尚未确认停止。", file=sys.stderr)
                return 0
            print(f"使用 preview-status {result['request_id']} 查询当前状态和有效播放链接。", file=sys.stderr)
            if result["retry_allowed"]:
                print(f"需要显式重试：preview-retry {result['request_id']}", file=sys.stderr)
            return int(result["task_status"] in ("FAILED", "CANCELLED") or
                       (result["artifact"] is not None and result["artifact"]["state"] != "AVAILABLE"))
        return 1 if result["status"] in ("FAILED", "INCOMPLETE") else 0
    except KeyboardInterrupt:
        message = "本地请求已中断；提交结果尚未确定，请通过 preview-list／原请求身份核对。" if args.command.startswith("preview") else "本地请求已中断；恢复前会核对模型回执，不能保证远端费用撤回。"
        print(message, file=sys.stderr)
        return 130
    except Exception as error:
        if args.command.startswith("preview"):
            code = error.code if isinstance(error, PreviewClientError) else "PREVIEW_COMMAND_ERROR"
            message = str(error) if isinstance(error, ValueError) else "预览命令执行异常，请核查原请求。"
            print(json.dumps({"error": {"code": code, "message": message}, "error_type": type(error).__name__}, ensure_ascii=False), file=sys.stderr)
            return 1
        message = str(error) if isinstance(error, ValueError) else "执行异常，checkpoint 已保留；请核查后使用 resume。"
        print(json.dumps({"status": "FAILED", "error_type": type(error).__name__, "message": message}, ensure_ascii=False), file=sys.stderr)
        return 1
