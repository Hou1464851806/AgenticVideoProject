"""The M4 acceptance report must reject the wrong published video."""
import copy
import hashlib
import importlib.util
from pathlib import Path
import subprocess
from types import SimpleNamespace

import httpx
import pytest

spec = importlib.util.spec_from_file_location('m4_acceptance', Path(__file__).parents[1] / 'scripts/verify_java_m4.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


@pytest.fixture(scope='module')
def video(tmp_path_factory):
    path = tmp_path_factory.mktemp('m4-video') / 'preview.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=640x360:r=30',
                    '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo', '-t', '1',
                    '-c:v', 'libx264', '-threads', '1', '-c:a', 'aac', str(path)], check=True)
    return path.read_bytes()


@pytest.mark.parametrize('fault', ['hash', 'size', 'duration', 'orientation', None])
def test_only_matching_published_media_can_pass(tmp_path, monkeypatch, video, fault):
    result = {'request_id': 'request', 'task_id': '00000000-0000-4000-8000-000000000001',
              'task_status': 'SUCCEEDED', 'retry_allowed': False,
              'artifact': {'artifact_id': 'artifact', 'state': 'AVAILABLE', 'duration_ms': 1000},
              'playback': {'url': 'http://storage.test/preview', 'expires_in_seconds': 300}}
    output = {'outputId': 'output', 'durationMs': 1000, 'render': {'profile': 'LANDSCAPE_720'}}
    artifact = {'id': 'artifact', 'sha256': hashlib.sha256(video).hexdigest(), 'byte_size': len(video)}
    if fault == 'hash':
        artifact['sha256'] = '0' * 64
    elif fault == 'size':
        artifact['byte_size'] += 1
    elif fault == 'duration':
        output['durationMs'] = 1200
    elif fault == 'orientation':
        output['render']['profile'] = 'PORTRAIT_720'
    # Inject only HTTP and database system boundaries; decode the actual MP4.
    monkeypatch.setattr(acceptance.httpx, 'get', lambda *a, **k:
                        httpx.Response(200, content=video, request=httpx.Request('GET', result['playback']['url'])))
    monkeypatch.setattr(acceptance, 'database', lambda sql: {'artifacts': [copy.deepcopy(artifact)]})
    args = SimpleNamespace(output=tmp_path)
    if fault is not None:
        with pytest.raises(AssertionError):
            acceptance.verify_video(args, result, output)
    else:
        report = acceptance.verify_video(args, result, output)
        assert report['sha256'] == artifact['sha256']
        assert report['result']['playback'] == {'expires_in_seconds': 300}
        assert (tmp_path / 'output.mp4').read_bytes() == video
