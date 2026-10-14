"""Development-only executor over immutable sample data, not production tools."""
import json
from pathlib import Path

from .contracts import PARAMETERS, ToolRequest, ToolResult
from ..runtime.policy import TemporaryToolError, fingerprint
from ..runtime.state import Draft, Requirements

SCENARIOS = {"normal", "pending", "validation_failure", "transient", "no_match"}


class MockExecutor:
    def __init__(self, scenario="normal", sample=None):
        if scenario not in SCENARIOS:
            raise ValueError("Unknown Mock scenario")
        self.scenario = scenario
        self.sample = sample or json.loads(Path(__file__).with_name("sample.json").read_text())
        self.assets = {a["id"]: a for a in self.sample["assets"]}
        self.records = {r["id"]: r for r in self.sample["records"]}
        self.attempts = {}

    async def execute(self, request: ToolRequest) -> ToolResult:
        def result(status="READY", **kwargs):
            return ToolResult(call_id=request.call_id, tool_name=request.tool_name,
                              request_hash=request.request_hash, status=status, **kwargs)
        args = PARAMETERS[request.tool_name].model_validate(request.arguments)
        n = self.attempts.get(request.call_id, 0) + 1
        self.attempts[request.call_id] = n
        if self.scenario == "transient" and n == 1:
            raise TemporaryToolError("Injected temporary Mock failure; no submission")
        if hasattr(args, "asset_id"):
            asset = self.assets.get(args.asset_id)
            if not asset:
                return result("ERROR", error={"code": "ASSET_NOT_FOUND", "category": "MODEL_REPAIRABLE"})
            if hasattr(args, "end_ms") and args.end_ms > asset["duration_ms"]:
                return result("ERROR", error={"code": "RANGE_OUTSIDE_ASSET", "category": "MODEL_REPAIRABLE", "duration_ms": asset["duration_ms"]})
        if request.tool_name == "list_assets":
            return result(data={"assets": list(self.assets.values()), "next_cursor": None})
        if request.tool_name == "get_video_overview":
            rows = [r for r in self.records.values() if r["asset_id"] == args.asset_id]
            page = rows[args.cursor:args.cursor+2]
            next_cursor = args.cursor+2 if args.cursor+2 < len(rows) else None
            return result(data={"entries": [{"start_ms": r["start_ms"], "end_ms": r["end_ms"], "excerpt": r["text"], "navigation_ids": [r["id"]]} for r in page], "next_cursor": next_cursor},
                          coverage={"asset_id": args.asset_id, "cursor": args.cursor, "next_cursor": next_cursor, "navigation_complete": next_cursor is None, "visual_complete": False})
        if request.tool_name == "search_evidence":
            rows = [r for r in self.records.values() if r["source_kind"] in args.source_kinds and
                    (not args.asset_ids or r["asset_id"] in args.asset_ids) and
                    (not args.query or any(term in r["text"] for term in args.query.split()))]
            if self.scenario == "no_match":
                rows = []
            page = rows[args.cursor:args.cursor+3]
            return result(data={"next_cursor": args.cursor+3 if args.cursor+3 < len(rows) else None}, records=page,
                          coverage={"source_kinds": args.source_kinds, "visual_complete": False})
        if request.tool_name == "read_video_range":
            rows = [r for r in self.records.values() if r["asset_id"] == args.asset_id and r["start_ms"] < args.end_ms and r["end_ms"] > args.start_ms]
            page = rows[args.cursor:args.cursor+3]
            return result(data={"next_cursor": args.cursor+3 if args.cursor+3 < len(rows) else None}, records=page,
                          coverage={"asset_id": args.asset_id, "start_ms": args.start_ms, "end_ms": args.end_ms, "visual_complete": False})
        if request.tool_name == "inspect_video_range":
            if self.scenario == "pending":
                return result("PENDING", dependency={"task_id": "mock-"+request.call_id, "kind": "INSPECTION"})
            record = self.inspection_record(request)
            return result(records=[record], coverage={"asset_id": args.asset_id, "start_ms": args.start_ms, "end_ms": args.end_ms, "visual_complete": False})
        if request.tool_name == "validate_edit_draft":
            if self.scenario == "validation_failure" and request.call_id.endswith(":v0"):
                return result(data={"valid": False, "issues": [{"field": "outputs", "code": "INJECTED_CHECK"}], "durations_ms": [],
                                    "draft_hash": args.draft_hash, "requirements_hash": args.requirements_hash})
            return result(data=self.validate(args))
        raise RuntimeError("Catalog and Mock executor are inconsistent")

    def inspection_record(self, request):
        args = request.arguments
        return {"id": "inspection-"+request.call_id, "asset_id": args["asset_id"], "source_kind": "VISUAL",
                "start_ms": args["start_ms"], "end_ms": args["start_ms"]+1, "analysis_version": "sample-v1",
                "text": "Mock 定向观察：该样本帧展示缓存结果面板；有限观察不能判断全片持续状态。"}

    def complete(self, request):
        """Explicit development event used by CLI, never an automatic polling loop."""
        return ToolResult(call_id=request.call_id, tool_name=request.tool_name, request_hash=request.request_hash,
                          status="READY", records=[self.inspection_record(request)],
                          coverage={"asset_id": request.arguments["asset_id"], "visual_complete": False})

    def validate(self, args):
        draft, req = Draft.model_validate(args.draft), Requirements.model_validate(args.requirements)
        issues, durations = [], []
        if fingerprint(args.draft) != args.draft_hash or fingerprint(args.requirements) != args.requirements_hash:
            raise RuntimeError("Draft validation identity mismatch")
        if len(draft.outputs) != req.output_count:
            issues.append({"field": "outputs", "code": "OUTPUT_COUNT", "expected": req.output_count})
        for i, output in enumerate(draft.outputs):
            total = 0
            for j, clip in enumerate(output.clips):
                asset = self.assets.get(clip.asset_id)
                field = f"outputs[{i}].clips[{j}]"
                if not asset or (req.asset_ids is not None and clip.asset_id not in req.asset_ids):
                    issues.append({"field": field, "code": "ASSET_SCOPE"})
                    continue
                if not 0 <= clip.source_in_ms < clip.source_out_ms <= asset["duration_ms"]:
                    issues.append({"field": field, "code": "INVALID_RANGE"})
                for evidence_id in clip.evidence_ids:
                    record = self.records.get(evidence_id) or next((r for r in args.read_records if r["id"] == evidence_id), None)
                    if evidence_id not in args.read_ids or not record or record["asset_id"] != clip.asset_id:
                        issues.append({"field": field+".evidence_ids", "code": "UNREAD_SOURCE", "id": evidence_id})
                    elif record["end_ms"] <= clip.source_in_ms or record["start_ms"] >= clip.source_out_ms:
                        issues.append({"field": field+".evidence_ids", "code": "SOURCE_OUTSIDE_RANGE", "id": evidence_id})
                    elif any(word in record["text"] for word in req.forbidden_content):
                        issues.append({"field": field, "code": "FORBIDDEN_CONTENT"})
                total += clip.source_out_ms - clip.source_in_ms
            durations.append(total)
            if (req.duration_min_ms and total < req.duration_min_ms) or (req.duration_max_ms and total > req.duration_max_ms):
                issues.append({"field": f"outputs[{i}]", "code": "DURATION", "actual_ms": total,
                               "min_ms": req.duration_min_ms, "max_ms": req.duration_max_ms})
        return {"valid": not issues, "issues": issues, "durations_ms": durations,
                "draft_hash": args.draft_hash, "requirements_hash": args.requirements_hash}
