"""Lease-bound Java transport for the browser Worker; the graph stays task agnostic."""
from typing import cast

import httpx

from .tools.java import JavaToolExecutor


class RunClient:
    def __init__(self, http: httpx.AsyncClient, token: str):
        if not token:
            raise ValueError('Worker requires INTERNAL_WORKER_TOKEN')
        self.http, self.token = http, token

    async def post(self, task, action, body):
        response = await self.http.post(f'/internal/v1/agent-runs/{task}/{action}', json=body,
                                       headers={'Authorization': 'Bearer ' + self.token})
        response.raise_for_status()
        return response.json()['data']


class RunToolExecutor(JavaToolExecutor):
    def __init__(self, client, task, credentials):
        super().__init__(str(client.http.base_url), client.token)
        self.client, self.task, self.credentials = client, task, credentials
        self.scope: dict | None = None
        self.generation: int | None = None

    async def snapshot(self, saved=None):
        if saved != self.scope:
            raise ValueError('Worker checkpoint scope differs from Java execution binding')
        scope = cast(dict, saved)
        self.scope_id = scope['scope_id']
        return scope

    async def _request(self, method, path, body=None):
        # Only execute() reaches this transport; browser productions never use CLI writes.
        if method != 'POST' or not path.endswith('/tools'):
            raise ValueError('Browser Worker only permits bound tool calls')
        response = await self.client.http.post(f'/internal/v1/agent-runs/{self.task}/tools',
            json={**self.credentials, 'editGeneration': self.generation, 'request': body},
            headers={'Authorization': 'Bearer ' + self.client.token})
        if response.status_code == 200:
            return httpx.Response(200, json=response.json()['data'], request=response.request)
        return response
