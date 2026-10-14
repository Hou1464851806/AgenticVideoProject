"""Paid M1 acceptance: fresh browser-API uploads -> Java analysis -> Python tools/DeepSeek.
Uses the existing local Compose service. Never prints credentials or signed storage URLs.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_service.config import load_env
from agent_service.tools.java import JavaToolExecutor
from agent_service.tools.contracts import ToolRequest
from agent_service.runtime.policy import fingerprint

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "frontend/e2e/fixtures"


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


async def main(args):
    import httpx
    for path in (ROOT / ".env", ROOT / "agent-service/.env.langfuse", ROOT / "agent-service/.env"):
        load_env(path)
    missing = [key for key in ("DASHSCOPE_API_KEY", "DEEPSEEK_API_KEY", "INTERNAL_WORKER_TOKEN") if not os.environ.get(key)]
    if missing:
        print("UNVERIFIED: missing " + ", ".join(missing))
        return 2
    args.output.mkdir(parents=True, exist_ok=True)
    with httpx.Client(base_url=args.url, timeout=60) as browser:
        def api(method, path, body=None):
            headers = {}
            if method != "GET":
                headers["X-XSRF-TOKEN"] = browser.get("/api/v1/auth/csrf").json()["data"]["csrfToken"]
                headers["Idempotency-Key"] = str(uuid.uuid4())
            response = browser.request(method, "/api/v1" + path, json=body, headers=headers)
            response.raise_for_status()
            return response.json()["data"]
        credentials = {"username": "m1-stage5-" + uuid.uuid4().hex[:12], "password": uuid.uuid4().hex}
        api("POST", "/auth/register", credentials)
        api("POST", "/auth/login", credentials)
        project = api("POST", "/projects", {"name": "M1 stage5 fresh acceptance"})["id"]
        samples = []
        for filename in ("labelled-speech.mp4", "human-speech.mp4", "labelled-vision-vfr.mp4", "speech-mention-only.mp4"):
            content = (FIXTURES / filename).read_bytes()
            upload = api("POST", f"/projects/{project}/uploads", {"filename": filename, "sizeBytes": len(content), "sha256": hashlib.sha256(content).hexdigest(), "idempotencyKey": str(uuid.uuid4()), "writerId": str(uuid.uuid4())})
            writer = {"writerToken": upload["writerToken"]}
            parts = api("POST", f"/uploads/{upload['uploadId']}/part-urls", {**writer, "partNumbers": [1]})
            response = httpx.put(parts["parts"][0]["url"], content=content, timeout=60)
            response.raise_for_status()
            completed = api("POST", f"/uploads/{upload['uploadId']}/complete", writer)
            asset_id = completed["assetId"]
            deadline = time.monotonic() + 360
            while True:
                asset = api("GET", f"/assets/{asset_id}")
                stages = asset.get("analysis", {}).get("stages", {})
                if "FAILED" in stages.values():
                    raise RuntimeError(f"{filename}: analysis failed {stages}")
                index = api("GET", f"/assets/{asset_id}/search-index") if asset.get("validationStatus") == "VALID" else {}
                if stages.get("VISION") == "SUCCEEDED" and stages.get("ASR") in ("SUCCEEDED", "NOT_APPLICABLE") and index.get("publishedGeneration"):
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError(f"{filename}: analysis timeout {stages}")
                time.sleep(1)
            executor = JavaToolExecutor(args.url, os.environ["INTERNAL_WORKER_TOKEN"], project, [asset_id])
            scope = await executor.snapshot()
            samples.append({"fixture": filename, "upload": completed, "asset": asset, "index": index, "scope": scope})
            save(args.output / "samples.json", {"samples": samples})
            print(filename + ": fresh Java analysis/index ready", flush=True)
    # Preserve provider identities and usage from the authoritative store, without request credentials.
    asset_ids = ",".join("'" + str(uuid.UUID(sample["scope"]["assets"][0]["id"])) + "'" for sample in samples)
    receipts = {}
    for name, sql in {
        "asr": f"SELECT m.id,m.analysis_id,m.model,m.status,m.provider_task_id,m.request_id,m.usage_json FROM model_calls m JOIN asset_analysis_heads h ON h.analysis_id=m.analysis_id WHERE h.asset_id IN ({asset_ids})",
        "vision": f"SELECT m.id,m.analysis_id,m.model,m.status,m.request_id,m.usage_json FROM vision_model_calls m JOIN asset_analysis_heads h ON h.analysis_id=m.analysis_id WHERE h.asset_id IN ({asset_ids})",
        "document_embeddings": f"SELECT e.generation_id,e.ordinal,e.status,e.request_id,e.usage_json FROM embedding_batches e JOIN search_index_generations g ON g.id=e.generation_id WHERE g.asset_id IN ({asset_ids})",
    }.items():
        raw = subprocess.check_output(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "agentic_video", "-d", "agentic_video", "-Atc", "SELECT coalesce(json_agg(row_to_json(r)),'[]') FROM (" + sql + ") r"], cwd=ROOT, text=True)
        receipts[name] = json.loads(raw)
    save(args.output / "source-receipts.json", receipts)
    semantic = []
    sample = samples[0]
    executor = JavaToolExecutor(args.url, os.environ["INTERNAL_WORKER_TOKEN"], project, [sample["scope"]["assets"][0]["id"]])
    await executor.snapshot()
    for query in json.loads((FIXTURES / "semantic-queries.json").read_text())["queries"]:
        arguments = {"query": query["query"], "source_kinds": ["SPEECH"]}
        request = ToolRequest(call_id=str(uuid.uuid4()), tool_name="search_evidence", arguments=arguments, request_hash=fingerprint({"tool": "search_evidence", "args": arguments}))
        result = await executor.execute(request)
        records = result.model_dump()["records"]
        hit = any(query["relevantText"] in record["text"] for record in records)
        semantic.append({**query, "hit": hit, "request": request.model_dump(), "result": result.model_dump()})
        save(args.output / "semantic.json", semantic)
        assert result.status == "READY" and hit, query
        assert result.data["channels"]["DENSE"]["status"] == "AVAILABLE"
    for mode in ("scripted", "deepseek"):
        command = [sys.executable, str(ROOT / "agent-service/scripts/verify_java_agent.py"), "--samples", str(args.output / "samples.json"), "--url", args.url, "--data-dir", str(args.output / mode), "--output", str(args.output / (mode + ".json"))]
        if mode == "deepseek":
            command.extend(["--deepseek", "--all-samples"])
        subprocess.run(command, check=True, cwd=ROOT)
    print("VERIFIED: fresh upload, Java analysis, semantic queries, Python four tools and DeepSeek; inspect retained reports for coverage.")
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path, required=True)
    sys.exit(asyncio.run(main(parser.parse_args())))
