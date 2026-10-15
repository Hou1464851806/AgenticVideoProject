"""Shared M4 contracts and explicit operations at the Java HTTP boundary."""
import json
from pathlib import Path

import httpx
import pytest
from pydantic import ValidationError

from agent_service.preview import PreviewRequest, PreviewResult, PreviewList

FIXTURE = Path(__file__).resolve().parents[2] / 'backend/src/test/resources/contracts/m4-preview.json'


def test_shared_preview_contracts():
    sample = json.loads(FIXTURE.read_text())
    assert PreviewRequest.model_validate(sample['request']).model_dump(exclude_none=True) == sample['request']
    for value in sample['selection_requests']:
        assert PreviewRequest.model_validate(value).model_dump(exclude_none=True) == value
    for value in sample['results']:
        assert PreviewResult.model_validate(value).model_dump() == value
    assert PreviewList.model_validate(sample['list']).model_dump() == sample['list']


@pytest.mark.parametrize('value', [{'output_id': 'foreign'}, {'output_key': 1}, {'output_key': ''}, {'output_key': None}])
def test_preview_request_rejects_replacement_identity_and_invalid_selection(value):
    with pytest.raises(ValidationError):
        PreviewRequest.model_validate(value)


@pytest.mark.asyncio
async def test_submit_preview_uses_internal_auth_and_only_output_selection():
    from agent_service.preview import PreviewClient
    sample = json.loads(FIXTURE.read_text())['results'][0]
    calls = []

    def handle(request):
        calls.append(request)
        assert request.method == 'POST'
        assert request.url.path == '/internal/v1/productions/' + sample['revision_id'] + '/previews'
        assert request.headers['Authorization'] == 'Bearer private-token'
        assert json.loads(request.content) == {'output_key': 'out-1'}
        return httpx.Response(202, json=sample)

    client = PreviewClient('http://java/', 'private-token', transport=httpx.MockTransport(handle))
    result = await client.submit(sample['revision_id'], 'out-1')
    assert result.model_dump() == sample
    assert len(calls) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('failure,code', [('transport', 'PREVIEW_TRANSPORT_UNCERTAIN'),
                                         ('protocol', 'PREVIEW_PROTOCOL_INVALID'),
                                         ('denied', 'PREVIEW_HTTP_401'),
                                         ('selection', 'OUTPUT_SELECTION_REQUIRED')])
async def test_submit_failure_is_explicit_and_never_retried(failure, code):
    from agent_service.preview import PreviewClient, PreviewClientError
    sample = json.loads(FIXTURE.read_text())['results'][0]
    calls = []

    def handle(request):
        calls.append(request)
        if failure == 'transport':
            raise httpx.ReadError('private-token must not be exposed', request=request)
        if failure == 'protocol':
            return httpx.Response(202, text='private-token invalid response')
        if failure == 'denied':
            return httpx.Response(401)
        return httpx.Response(400, json={'error': {'code': code, 'message': '请选择 out-1 或 out-2'}})

    client = PreviewClient('http://java', 'private-token', transport=httpx.MockTransport(handle))
    with pytest.raises(PreviewClientError) as caught:
        await client.submit(sample['revision_id'])
    assert caught.value.code == code
    assert 'private-token' not in str(caught.value)
    if failure == 'selection':
        assert 'out-2' in str(caught.value)
    assert len(calls) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('operation,method,suffix', [
    ('list', 'GET', '/previews'), ('status', 'GET', ''),
    ('cancel', 'POST', '/cancel'), ('retry', 'POST', '/retry'),
    ('saved', 'GET', '/save'),
])
async def test_preview_reads_and_explicit_actions(operation, method, suffix):
    from agent_service.preview import PreviewClient
    sample = json.loads(FIXTURE.read_text())
    identity = sample['list']['requirement_id'] if operation in ('list', 'saved') else sample['results'][0]['request_id']
    prefix = '/questions/productions/' if operation == 'saved' else '/productions/' if operation == 'list' else '/previews/'
    saved = {'status': 'SAVED', 'plan_id': identity, 'revision_id': identity, 'revision_number': 1,
             'outputs': [{'output_key': 'out-1', 'output_id': identity, 'duration_ms': 4000}]}
    value = saved if operation == 'saved' else sample['list'] if operation == 'list' else sample['results'][0]

    def handle(request):
        assert request.method == method
        assert request.url.path == '/internal/v1' + prefix + identity + suffix
        assert request.headers['Authorization'] == 'Bearer private-token'
        assert not request.content
        return httpx.Response(200, json=value)

    client = PreviewClient('http://java', 'private-token', transport=httpx.MockTransport(handle))
    assert (await getattr(client, operation)(identity)).model_dump() == value


@pytest.mark.asyncio
async def test_missing_save_result_is_distinct_from_dependency_failure():
    from agent_service.preview import PreviewClient, PreviewClientError
    identity = json.loads(FIXTURE.read_text())['list']['requirement_id']
    client = PreviewClient('http://java', 'token', transport=httpx.MockTransport(lambda req: httpx.Response(404)))
    assert await client.saved(identity) is None
    client = PreviewClient('http://java', 'token', transport=httpx.MockTransport(lambda req: httpx.Response(503)))
    with pytest.raises(PreviewClientError, match='503'):
        await client.saved(identity)


@pytest.mark.asyncio
@pytest.mark.parametrize('operation', ['submit', 'status', 'cancel', 'retry', 'list'])
async def test_invalid_identifiers_never_become_url_paths(operation):
    from agent_service.preview import PreviewClient
    calls = []
    client = PreviewClient('http://java', 'token', transport=httpx.MockTransport(lambda req: calls.append(req)))
    with pytest.raises(ValueError):
        await getattr(client, operation)('../exports/foreign?task=1')
    assert not calls


@pytest.mark.asyncio
@pytest.mark.parametrize('body', [None, [], {'task_status': 'SUCCEEDED'}, {'error': 'not a result'}])
async def test_invalid_success_payload_is_a_protocol_error(body):
    from agent_service.preview import PreviewClient, PreviewClientError
    identity = json.loads(FIXTURE.read_text())['results'][0]['request_id']
    client = PreviewClient('http://java', 'token', transport=httpx.MockTransport(lambda req: httpx.Response(200, json=body)))
    with pytest.raises(PreviewClientError) as caught:
        await client.status(identity)
    assert caught.value.code == 'PREVIEW_PROTOCOL_INVALID'


@pytest.mark.asyncio
async def test_redirects_do_not_forward_credentials_or_retry_posts():
    from agent_service.preview import PreviewClient, PreviewClientError
    calls = []
    def handle(req):
        calls.append(req)
        return httpx.Response(307, headers={'Location': 'http://other-server/previews'})
    client = PreviewClient('http://java', 'token', transport=httpx.MockTransport(handle))
    with pytest.raises(PreviewClientError, match='307'):
        await client.retry(json.loads(FIXTURE.read_text())['results'][0]['request_id'])
    assert len(calls) == 1
