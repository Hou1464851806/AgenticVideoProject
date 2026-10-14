from .contracts import PARAMETERS

DESCRIPTIONS = {
    "list_assets": "List scoped sample assets, durations and available source kinds.",
    "get_video_overview": "Read paginated whole-video navigation. Overview is not precise evidence. Consume all pages for whole-video claims.",
    "search_evidence": "Find bounded raw observations by topic or exact phrase. Empty matches do not prove absence. Choose SPEECH/VISUAL/VISIBLE_TEXT correctly.",
    "read_video_range": "Read original records in a bounded millisecond range. Check speech context before selecting clips.",
    "inspect_video_range": "Mock a goal-directed visual inspection; may return PENDING requiring an external result. No real paid VLM call.",
    "validate_edit_draft": "Program-only draft validation; never skip this before DRAFT_READY.",
}

DISABLED = {
    "save_plan_revision": {"requires": ["validated_draft", "business_authority"], "returns": "revision_receipt"},
    "confirm_plan_revision": {"requires": ["existing_revision", "explicit_confirmation"], "returns": "confirmation_receipt"},
    "request_preview": {"requires": ["existing_revision", "explicit_preview_request"], "returns": "task_or_artifact"},
    "request_export": {"requires": ["existing_revision", "confirmation", "separate_export_request"], "returns": "task_or_artifact"},
    "get_task_status": {"requires": ["existing_task"], "returns": "task_status"},
}


def model_tools() -> list[dict]:
    return [{"name": name, "description": DESCRIPTIONS[name], "parameters": schema.model_json_schema()}
            for name, schema in PARAMETERS.items() if name != "validate_edit_draft"]
