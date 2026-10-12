"""Cancel local model I/O cooperatively, including while response headers are pending."""
import asyncio
import contextlib

import httpx

from .public_reply import streamed_response


def receive_model(address, key, payload, execution, public):
    async def receive():
        async with httpx.AsyncClient(timeout=httpx.Timeout(90, connect=5)) as client:
            async with client.stream("POST", address, headers={"Authorization": "Bearer " + key}, json=payload) as reply:
                reply.raise_for_status()
                return await streamed_response(reply, execution, public)

    return _run(receive, execution.check, 90)


def receive_business(address, body, headers, check):
    async def receive():
        async with httpx.AsyncClient(timeout=httpx.Timeout(150, connect=2)) as client:
            return await client.post(address, headers=headers, json=body)
    return _run(receive, check, 150)


def _run(receive, check, seconds):
    async def cancellable():
        request = asyncio.create_task(receive())
        deadline = asyncio.get_running_loop().time() + seconds
        try:
            while not request.done():
                check()
                if asyncio.get_running_loop().time() >= deadline:
                    raise httpx.ReadTimeout("Request exceeded its local deadline")
                await asyncio.sleep(.1)
            check()
            return await request
        finally:
            if not request.done():
                request.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await request

    return asyncio.run(cancellable())
