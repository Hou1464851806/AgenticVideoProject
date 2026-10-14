"""Shared identity and temporal validation for observations and final citations."""
def valid_evidence(record, asset, *, mock=True):
    if not asset or record.get("analysis_version") != asset.get("analysis_version"):
        return False
    duration = asset.get("duration_ms")
    if not isinstance(duration, int) or isinstance(duration, bool):
        return False
    kind = record.get("source_kind")
    if kind not in asset.get("source_kinds", []):
        return False
    def integer(value):
        return isinstance(value, int) and not isinstance(value, bool)
    if record.get("time_kind") == "POINT":
        at = record.get("observed_at_ms")
        return kind in ("VISUAL", "VISIBLE_TEXT") and integer(at) and 0 <= at < duration and "start_ms" not in record and "end_ms" not in record
    if not mock and kind != "SPEECH":
        return False
    start, end = record.get("start_ms"), record.get("end_ms")
    return record.get("time_kind", "INTERVAL") == "INTERVAL" and integer(start) and integer(end) and 0 <= start < end <= duration
