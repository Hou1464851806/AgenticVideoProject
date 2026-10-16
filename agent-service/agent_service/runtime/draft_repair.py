"""Targeted draft repairs over trusted observations; Java remains the final validator."""
from copy import deepcopy

from .policy import fingerprint
from .state import Requirements


def _validation(state, assets):
    validation = state.get("validation")
    if state.get("previous_validation") is None or not validation or validation["valid"]:
        return None
    if state.get("intent", state["requirements"]["mode"]) != "DRAFT":
        return None
    requirements = Requirements.model_validate(state["requirements"]).production(sorted(assets))
    if (validation["draft_hash"] != fingerprint(state["draft"])
            or validation["requirements_hash"] != fingerprint(requirements)):
        return None
    return validation


def _protected_keys(validation, outputs):
    keys = [o["output_key"] for o in outputs]
    if validation["issues_truncated"] or any(i.get("output_key") not in keys for i in validation["issues"]):
        return []
    failed = {i["output_key"] for i in validation["issues"]}
    return [key for key in keys if key not in failed]


def _overlaps(record, start, end):
    if record.get("time_kind") == "POINT":
        return start <= record["observed_at_ms"] < end
    return record["start_ms"] < end and record["end_ms"] > start


def prepare_draft(proposed, state, assets):
    draft = deepcopy(proposed)
    validation = _validation(state, assets)
    if validation:
        previous = state["draft"]["outputs"]
        by_key = {o["output_key"]: o for o in draft["outputs"]}
        if set(by_key) != {o["output_key"] for o in previous}:
            raise ValueError("修正草稿必须保留原有 output_key；仅修正本轮校验失败的输出")
        protected = _protected_keys(validation, previous)
        draft["outputs"] = [deepcopy(o) if o["output_key"] in protected else by_key[o["output_key"]] for o in previous]
    for output in draft["outputs"]:
        for index, clip in enumerate(output["clips"]):
            retained = []
            for identity in clip["evidence_ids"]:
                record = state["observations"].get(identity)
                # Unknown or wrong-scope references remain errors for Java, never disappear here.
                if (record and clip["asset_id"] in assets and record["asset_id"] == clip["asset_id"]
                        and record["analysis_version"] == assets[clip["asset_id"]]["analysis_version"]
                        and not _overlaps(record, clip["source_in_ms"], clip["source_out_ms"])):
                    continue
                retained.append(identity)
            if not retained:
                raise ValueError(f"{output['output_key']} 的片段 {index} 没有与选段相交的依据；请从已读证据重新选择引用，不要延长片段来容纳旧引用")
            clip["evidence_ids"] = retained
    return draft


def _covered(start, end, ranges):
    through = start
    for row in sorted(ranges, key=lambda r: r["start_ms"]):
        if row["start_ms"] > through:
            break
        through = max(through, row["end_ms"])
        if through >= end:
            return True
    return False


def _safe(time, speech, offset):
    for record in speech:
        if not record["start_ms"] < time < record["end_ms"]:
            continue
        words = record.get("observation", {}).get("words", [])
        if not words:
            return False
        if any(max(0, w["begin_time"] + offset) < time < min(record["end_ms"], w["end_time"] + offset) for w in words):
            return False
    return True


def _candidates(clip, other_duration, condition, issues, state, assets):
    if clip["asset_id"] not in assets:
        return []
    asset = assets[clip["asset_id"]]
    records = state["observations"]
    if any(identity not in records or records[identity]["asset_id"] != clip["asset_id"]
           or records[identity]["analysis_version"] != asset["analysis_version"] for identity in clip["evidence_ids"]):
        return []
    ranges = [r for r in state["coverage"].get("_range_reads", {}).values()
              if r["complete"] and r["asset_id"] == clip["asset_id"]
              and r["start_ms"] < clip["source_out_ms"] and r["end_ms"] > clip["source_in_ms"]]
    speech = [r for r in records.values() if r["asset_id"] == clip["asset_id"] and r["source_kind"] == "SPEECH"
              and r["analysis_version"] == asset["analysis_version"]]
    points = {clip["source_in_ms"], clip["source_out_ms"]}
    for record in speech:
        if _covered(record["start_ms"], record["end_ms"], ranges):
            points.update((record["start_ms"], record["end_ms"]))
    for issue in issues:
        if issue["code"] == "CUT_INSIDE_WORD":
            points.update((issue.get("expected") or {}).get("nearby_safe_boundaries_ms", []))
    points = sorted(p for p in points if 0 <= p <= asset["duration_ms"] and _safe(p, speech, asset.get("audio_offset_ms", 0)))
    candidates = []
    for start in points:
        for end in points:
            duration = other_duration + end - start
            if (end <= start or start >= clip["source_out_ms"] or end <= clip["source_in_ms"]
                    or condition["duration_min_ms"] is not None and duration < condition["duration_min_ms"]
                    or condition["duration_max_ms"] is not None and duration > condition["duration_max_ms"]
                    or not _covered(start, end, ranges)):
                continue
            ids = [identity for identity in clip["evidence_ids"] if _overlaps(records[identity], start, end)]
            if ids:
                candidates.append({"source_in_ms": start, "source_out_ms": end,
                                   "output_duration_ms": duration, "evidence_ids": ids})
    candidates.sort(key=lambda c: (abs(c["source_in_ms"] - clip["source_in_ms"]) + abs(c["source_out_ms"] - clip["source_out_ms"]), c["source_in_ms"], c["source_out_ms"]))
    return candidates[:3]


def repair_plan(state, assets):
    validation = _validation(state, assets)
    if validation is None:
        return None
    outputs = state["draft"]["outputs"]
    protected = _protected_keys(validation, outputs)
    conditions = {o["output_key"]: o for o in state["requirements"]["outputs"]}
    plan = {"preserve_output_keys": protected, "outputs": {}, "requires_full_validation": True}
    for output in outputs:
        key = output["output_key"]
        if key in protected:
            continue
        duration = sum(c["source_out_ms"] - c["source_in_ms"] for c in output["clips"])
        condition = conditions[key]
        issues = [i for i in validation["issues"] if i.get("output_key") in (key, None)]
        entry = {"duration_ms": duration, "min_ms": condition["duration_min_ms"], "max_ms": condition["duration_max_ms"],
                 "too_long_ms": max(0, duration - condition["duration_max_ms"]) if condition["duration_max_ms"] is not None else 0,
                 "too_short_ms": max(0, condition["duration_min_ms"] - duration) if condition["duration_min_ms"] is not None else 0, "clips": []}
        for index, clip in enumerate(output["clips"]):
            remove = [identity for identity in clip["evidence_ids"] if clip["asset_id"] in assets and identity in state["observations"]
                      and state["observations"][identity]["asset_id"] == clip["asset_id"]
                      and state["observations"][identity]["analysis_version"] == assets[clip["asset_id"]]["analysis_version"]
                      and not _overlaps(state["observations"][identity], clip["source_in_ms"], clip["source_out_ms"])]
            entry["clips"].append({"index": index, "remove_evidence_ids": remove,
                "candidates": _candidates(clip, duration - clip["source_out_ms"] + clip["source_in_ms"], condition, issues, state, assets)})
        plan["outputs"][key] = entry
    return plan
