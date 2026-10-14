"""Beijing DashScope asynchronous ASR and private temporary audio transfer."""
import json
import uuid
from pathlib import Path
from urllib.parse import urlparse

import requests


class ProviderError(RuntimeError):
    pass


class SubmissionUncertain(ProviderError):
    """A lost submit response must not trigger another chargeable submission."""


class _Multipart:
    def __init__(self, fields, path, boundary):
        self.path = path
        self.prefix = b"".join(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode()
            for key, value in fields.items()
        ) + f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="audio.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode()
        self.suffix = f"\r\n--{boundary}--\r\n".encode()

    def __len__(self):
        return len(self.prefix) + self.path.stat().st_size + len(self.suffix)

    def __iter__(self):
        yield self.prefix
        with self.path.open("rb") as source:
            while chunk := source.read(65536):
                yield chunk
        yield self.suffix


class BailianAsr:
    def __init__(self, api_key, base_url="https://dashscope.aliyuncs.com/api/v1", model="qwen3-asr-flash-filetrans"):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.http = requests.Session()

    def _json(self, method, url, *, authorized=True, **kwargs):
        headers = kwargs.pop("headers", {})
        if authorized:
            headers["Authorization"] = f"Bearer {self.api_key}"
        try:
            with self.http.request(method, url, headers=headers, timeout=(10, 30), stream=True, **kwargs) as response:
                if response.status_code != 200:
                    raise ProviderError(f"Provider HTTP {response.status_code}")
                output = bytearray()
                for chunk in response.iter_content(65536):
                    output.extend(chunk)
                    if len(output) > 8 * 1024 * 1024:
                        raise ProviderError("Provider response exceeds limit")
                return json.loads(output)
        except (requests.RequestException, ValueError) as failure:
            raise ProviderError("Provider response unavailable") from failure

    def upload(self, path):
        path = Path(path)
        policy = self._json("GET", self.base_url + "/uploads", params={"action": "getPolicy", "model": self.model})["data"]
        host = urlparse(policy["upload_host"])
        if host.scheme != "https" or not host.hostname or not host.hostname.endswith(".aliyuncs.com"):
            raise ProviderError("Unexpected provider upload destination")
        if path.stat().st_size > int(policy["max_file_size_mb"]) * 1024 * 1024:
            raise ProviderError("Audio exceeds provider upload limit")
        key = policy["upload_dir"] + "/" + str(uuid.uuid4()) + ".wav"
        fields = {
            "OSSAccessKeyId": policy["oss_access_key_id"], "Signature": policy["signature"],
            "policy": policy["policy"], "x-oss-object-acl": policy["x_oss_object_acl"],
            "x-oss-forbid-overwrite": policy["x_oss_forbid_overwrite"], "key": key, "success_action_status": "200",
        }
        boundary = uuid.uuid4().hex
        try:
            with self.http.post(policy["upload_host"], data=_Multipart(fields, path, boundary),
                    headers={"Content-Type": f"multipart/form-data; boundary={boundary}"}, timeout=(10, 90)) as response:
                if response.status_code != 200:
                    raise ProviderError(f"Provider upload HTTP {response.status_code}")
        except requests.RequestException as failure:
            raise ProviderError("Provider upload unavailable") from failure
        return "oss://" + key

    def submit(self, address):
        try:
            return self._json("POST", self.base_url + "/services/audio/asr/transcription",
                headers={"X-DashScope-Async": "enable", "X-DashScope-OssResourceResolve": "enable"},
                json={"model": self.model, "input": {"file_url": address},
                    "parameters": {"channel_id": [0], "enable_itn": False, "enable_words": True}})
        except ProviderError as failure:
            raise SubmissionUncertain("ASR submission unconfirmed; do not automatically resubmit") from failure

    def query(self, task_id):
        return self._json("GET", self.base_url + "/tasks/" + str(uuid.UUID(task_id)))

    def transcript(self, result):
        url = result["output"]["result"]["transcription_url"]
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or not parsed.hostname.endswith(".aliyuncs.com"):
            raise ProviderError("Unexpected transcript destination")
        return self._json("GET", parsed._replace(scheme="https").geturl(), authorized=False)
