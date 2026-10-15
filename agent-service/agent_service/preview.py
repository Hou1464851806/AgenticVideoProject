"""M4 preview transport, independent of Agent state and model tools."""
from typing import Annotated, Literal

import httpx

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator

from .editing import SavedResult

Identifier = Annotated[str, Field(pattern=r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')]
OutputKey = Annotated[str, Field(min_length=1, max_length=64)]
TaskStatus = Literal['PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCEEDED', 'FAILED', 'CANCELLED']


class Contract(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class PreviewRequest(Contract):
    output_key: OutputKey | None = None

    @model_validator(mode='before')
    @classmethod
    def selection_is_optional_but_not_null(cls, value):
        if isinstance(value, dict) and 'output_key' in value and value['output_key'] is None:
            raise ValueError('output_key must be omitted or a nonempty string')
        return value


class Artifact(Contract):
    artifact_id: Identifier
    state: Literal['AVAILABLE', 'UNAVAILABLE', 'REMOVED']
    duration_ms: Annotated[int, Field(gt=0)]


class Playback(Contract):
    url: str
    expires_in_seconds: Annotated[int, Field(gt=0)]


class PreviewError(Contract):
    code: str
    message: str


class PreviewResult(Contract):
    request_id: Identifier
    project_id: Identifier
    revision_id: Identifier
    output_id: Identifier
    task_id: Identifier
    task_status: TaskStatus
    created_at: str
    artifact: Artifact | None
    playback: Playback | None
    retry_allowed: bool
    error: PreviewError | None


class PreviewListItem(Contract):
    request_id: Identifier
    output_key: OutputKey
    output_id: Identifier
    task_id: Identifier
    task_status: TaskStatus
    created_at: str


class PreviewList(Contract):
    requirement_id: Identifier
    revision_id: Identifier
    items: list[PreviewListItem]


class PreviewClientError(ValueError):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


class PreviewClient:
    """Explicit preview operations; no graph, polling or automatic resubmission."""

    def __init__(self, base_url, token, *, transport=None):
        if not base_url:
            raise ValueError('预览需要 --java-url')
        if not token:
            raise ValueError('预览需要 INTERNAL_WORKER_TOKEN')
        self.base_url = base_url.rstrip('/') + '/internal/v1'
        self._token = token
        self.transport = transport

    async def _request(self, method, path, result_type, body=None, *, missing_ok=False):
        try:
            async with httpx.AsyncClient(transport=self.transport, timeout=60, follow_redirects=False) as client:
                response = await client.request(method, self.base_url + path, json=body,
                                                headers={'Authorization': 'Bearer ' + self._token})
        except httpx.TransportError as error:
            code = 'PREVIEW_TRANSPORT_UNCERTAIN' if method == 'POST' else 'PREVIEW_TRANSPORT_ERROR'
            message = '提交结果尚未确定，请通过列表／请求身份核对' if method == 'POST' else '网络访问失败，请稍后核对原请求'
            raise PreviewClientError(code, message) from error
        if missing_ok and response.status_code == 404:
            return None
        if response.status_code not in ((200, 202) if method == 'POST' else (200,)):
            try:
                failure = PreviewError.model_validate(response.json()['error'])
            except (ValueError, KeyError, TypeError):
                raise PreviewClientError('PREVIEW_HTTP_' + str(response.status_code),
                                         f'Java 预览访问失败（HTTP {response.status_code}）') from None
            raise PreviewClientError(failure.code, f'{failure.message}（HTTP {response.status_code}）')
        try:
            return result_type.model_validate(response.json())
        except ValueError as error:
            raise PreviewClientError('PREVIEW_PROTOCOL_INVALID', 'Java 返回了无效预览响应，请通过列表／请求身份核对') from error

    async def submit(self, requirement_id, output_key=None):
        requirement_id = TypeAdapter(Identifier).validate_python(requirement_id)
        body = PreviewRequest(output_key=output_key).model_dump(exclude_none=True) if output_key is not None else {}
        return await self._request('POST', '/productions/' + requirement_id + '/previews', PreviewResult, body)

    async def list(self, requirement_id):
        requirement_id = TypeAdapter(Identifier).validate_python(requirement_id)
        return await self._request('GET', '/productions/' + requirement_id + '/previews', PreviewList)

    async def status(self, request_id):
        request_id = TypeAdapter(Identifier).validate_python(request_id)
        return await self._request('GET', '/previews/' + request_id, PreviewResult)

    async def cancel(self, request_id):
        request_id = TypeAdapter(Identifier).validate_python(request_id)
        return await self._request('POST', '/previews/' + request_id + '/cancel', PreviewResult)

    async def retry(self, request_id):
        request_id = TypeAdapter(Identifier).validate_python(request_id)
        return await self._request('POST', '/previews/' + request_id + '/retry', PreviewResult)

    async def saved(self, requirement_id):
        requirement_id = TypeAdapter(Identifier).validate_python(requirement_id)
        return await self._request('GET', '/questions/productions/' + requirement_id + '/save',
                                   SavedResult, missing_ok=True)
