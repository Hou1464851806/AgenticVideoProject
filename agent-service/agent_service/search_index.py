"""Persist paid batches before writing an unpublished native Qdrant generation."""
import os
import requests


def build_index(execution):
    def record(action, data=None):
        return execution.post("provider-call", {"action": action, "data": data or {}})

    profile = record("LOOKUP")
    endpoint = os.environ.get("DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/api/v1")
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not key and any(batch["status"] != "SUCCEEDED" for batch in profile["batches"]):
        raise RuntimeError("Embedding credentials unavailable")
    for batch in profile["batches"]:
        execution.check()
        if batch["status"] == "SUCCEEDED":
            continue
        if batch["status"] != "READY":
            raise RuntimeError("Embedding submission uncertain; automatic paid resubmission stopped")
        record("PREPARE", {"ordinal": batch["ordinal"]})
        response = requests.post(endpoint + "/services/embeddings/text-embedding/text-embedding",
            headers={"Authorization": "Bearer " + key},
            json={"model": profile["model"], "input": {"texts": [unit["text"] for unit in batch["units"]]},
                  "parameters": {"dimension": profile["dimension"], "text_type": "document"}}, timeout=(3, 60))
        response.raise_for_status()
        result = response.json()
        embeddings = sorted(result["output"]["embeddings"], key=lambda item: item["text_index"])
        if [item["text_index"] for item in embeddings] != list(range(len(batch["units"]))):
            raise RuntimeError("Embedding batch identities invalid")
        execution.check()
        record("SUCCEEDED", {"ordinal": batch["ordinal"], "vectors": [item["embedding"] for item in embeddings],
                             "requestId": result.get("request_id", ""), "usage": result.get("usage", {})})
    profile = record("LOOKUP")
    record("INDEXING")
    base = os.environ.get("QDRANT_URL", "http://qdrant:6333")
    collection = profile["collection"]
    path = base + "/collections/" + collection
    response = requests.get(path, timeout=(3, 10))
    if response.status_code == 404:
        response = requests.put(path, json={"vectors": {"dense": {"size": profile["dimension"], "distance": "Cosine"}},
                                "sparse_vectors": {"bm25": {"modifier": "idf"}}}, timeout=(3, 30))
    response.raise_for_status()
    for field in ("projectId", "generationId", "assetId", "sourceKind"):
        response = requests.put(path + "/index?wait=true", json={"field_name": field, "field_schema": "keyword"}, timeout=(3, 30))
        response.raise_for_status()
    for batch in profile["batches"]:
        execution.check()
        points = []
        for unit, vector in zip(batch["units"], batch["vectors"], strict=True):
            points.append({"id": unit["id"], "vector": {"dense": vector,
                "bm25": {"text": unit["text"], "model": "qdrant/bm25", "options": profile["bm25"]}},
                "payload": {"unitId": unit["unitId"], "projectId": profile["projectId"], "generationId": profile["generationId"],
                            "assetId": profile["assetId"], "analysisId": profile["analysisId"], "sourceKind": unit["sourceKind"]}})
        response = requests.put(path + "/points?wait=true", json={"points": points}, timeout=(3, 45))
        response.raise_for_status()
    execution.check()
    execution.post("complete", {"producerKey": "index-built", "resultType": "INDEX_BUILT", "result": {"generationId": profile["generationId"]}})
