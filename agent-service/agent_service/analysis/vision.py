"""Image-only observations preserve frame occurrence times and durable paid-call receipts."""
import base64
import hashlib
import json
import os
import boto3
import requests
from botocore.config import Config


def analyze_vision(execution):
    if not os.environ.get("DASHSCOPE_API_KEY"):
        raise RuntimeError("未配置百炼凭据，真实视觉分析未验证")
    manifest = execution.provider("LOOKUP")
    storage = boto3.client("s3", endpoint_url=os.environ["STORAGE_ENDPOINT"],
        aws_access_key_id=os.environ["STORAGE_ACCESS_KEY"], aws_secret_access_key=os.environ["STORAGE_SECRET_KEY"],
        region_name="us-east-1", config=Config(connect_timeout=3, read_timeout=15, retries={"max_attempts":2}, s3={"addressing_style":"path"}))
    seen = set()
    for frame in manifest["frames"]:
        execution.check()
        if frame["callId"] in seen or frame["status"] == "SUCCEEDED":
            continue
        seen.add(frame["callId"])
        if frame["status"] != "READY":
            raise RuntimeError("视觉模型提交结果不明确；不能自动重复付费")
        body = storage.get_object(Bucket=os.environ.get("STORAGE_BUCKET", "originals"), Key=frame["objectKey"])["Body"]
        try:
            image = body.read(1048577)
        finally:
            body.close()
        if len(image) != frame["sizeBytes"] or hashlib.sha256(image).hexdigest() != frame["sha256"]:
            raise RuntimeError("代表帧身份校验失败")
        grant=execution.provider("PREPARE", {"callId":frame["callId"]})
        if not grant.get("canSubmit"):
            raise RuntimeError("补查预算已耗尽，停止新增视觉调用")
        try:
            response = requests.post(os.environ.get("DASHSCOPE_BASE_URL","https://dashscope.aliyuncs.com/api/v1").rstrip("/")+"/services/aigc/multimodal-generation/generation",
                headers={"Authorization":"Bearer "+os.environ["DASHSCOPE_API_KEY"]}, timeout=(min(3,max(0.01,execution.remaining_budget()/2)),min(90,max(0.01,execution.remaining_budget()/2))),
                json={"model":manifest["model"],"input":{"messages":[{"role":"user","content":[
                    {"image":"data:image/jpeg;base64,"+base64.b64encode(image).decode()},
                    {"text":"只观察输入的单张图片，不推断音频、前后事件或持续区间。返回严格JSON对象，description为实际画面场景与物体的中文描述，不混入屏幕文字；visibleText为可见文字逐条原样转写的字符串数组（包括数字），没有文字返回空数组。格式：{\"description\":\"...\",\"visibleText\":[\"...\"]}。"}]}]},
                    "parameters":{"enable_thinking":False,"max_tokens":1200}})
            response.raise_for_status()
            result=response.json()
            content=result["output"]["choices"][0]["message"]["content"]
            text="".join(item.get("text","") for item in content).strip()
            if text.startswith("```"):
                text=text.split("\n",1)[1].rsplit("```",1)[0].strip()
            observation=json.loads(text)
        except Exception as failure:
            execution.provider("UNCERTAIN", {"callId":frame["callId"]})
            status = failure.response.status_code if isinstance(failure, requests.HTTPError) and failure.response is not None else None
            raise RuntimeError(f"视觉响应未可靠保存或格式不符（{type(failure).__name__}，HTTP {status}）；保留调用身份，未自动重付") from None
        execution.check()
        execution.provider("SUCCEEDED", {"callId":frame["callId"],"observation":observation,"requestId":result.get("request_id",""),"usage":result.get("usage",{})})
    execution.check()
    execution.post("complete", {"producerKey":"vision-analyzed","resultType":"INSPECTION_ANALYZED" if execution.input.get("inspectionId") else "VISION_ANALYZED","result":{}})
