import argparse
import json
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = 'Hou1464851806/AgenticVideoProject'
API = f'repos/{REPO}'
TICKETS = json.loads((ROOT / 'tickets.json').read_text())
STATE_PATH = ROOT / 'published.json'
OUT = ROOT / 'published'
OUT.mkdir(exist_ok=True)
STATE = json.loads(STATE_PATH.read_text()) if STATE_PATH.exists() else {}
PARENT_BEFORE = json.loads(Path('/tmp/agentic-video-parent-before-publish.json').read_text())


def api(endpoint, method='GET', payload=None):
    cmd = ['gh', 'api', endpoint, '-X', method,
           '-H', 'Accept: application/vnd.github+json',
           '-H', 'X-GitHub-Api-Version: 2026-03-10']
    if payload is not None:
        request = OUT / 'request.json'
        request.write_text(json.dumps(payload, ensure_ascii=False) + '\n')
        cmd += ['--input', str(request)]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=55)
    if result.returncode:
        raise RuntimeError(f'{method} {endpoint}: {result.stderr.strip()}')
    if method != 'GET':
        time.sleep(1.1)
    return json.loads(result.stdout) if result.stdout.strip() else None


def save():
    temp = STATE_PATH.with_suffix('.tmp')
    temp.write_text(json.dumps(STATE, ensure_ascii=False, indent=2) + '\n')
    temp.replace(STATE_PATH)


def issue_body(ticket):
    draft = ROOT / 'issues' / f"{ticket['id']:02d}-{ticket['slug']}.md"
    body = '## Parent' + draft.read_text().split('## Parent', 1)[1]
    body = re.sub(r'## Blocked by\n.*?(?=## Requirement coverage)', '', body, flags=re.S)
    priority = ticket['priority']
    body = body.replace(f'{priority}；发布时使用 `priority:{priority}` 和 `ready-for-agent`。',
                        f'**{priority}** · `priority:{priority}` · `ready-for-agent`')
    body = body.replace('跨功能复验另由 T31 汇总', '跨功能复验由最终验收任务汇总')
    if not ticket['blockers']:
        body += '\n## Blocked by\n\nNone (can start immediately)。\n'
    if ticket['id'] == 24:
        preview = STATE['23']
        body = body.replace('前置 T23', f"前置 [#{preview['number']}]({preview['url']})")
    assert '待用户确认' not in body and '发布时使用' not in body
    assert not re.search(r'\bT\d{2}\b', body), ticket['id']
    return body.rstrip() + '\n'


def publish(limit):
    # Reconcile with GitHub before any create, including an uncertain prior response.
    existing = api(API + '/issues?state=all&per_page=100')
    for ticket in TICKETS:
        key = str(ticket['id'])
        title = f"[T{ticket['id']:02d}] {ticket['title']}"
        matches = [issue for issue in existing if issue['title'] == title and 'pull_request' not in issue]
        if len(matches) > 1:
            raise RuntimeError(f'Duplicate existing title: {title}')
        if key not in STATE and matches:
            issue = matches[0]
            STATE[key] = {'number': issue['number'], 'id': issue['id'], 'url': issue['html_url'],
                          'title': issue['title'], 'priority': ticket['priority'], 'dependencies': []}
            save()
    for ticket in TICKETS:
        if ticket['id'] > limit:
            break
        key = str(ticket['id'])
        title = f"[T{ticket['id']:02d}] {ticket['title']}"
        body = issue_body(ticket)
        body_path = OUT / f"{ticket['id']:02d}-{ticket['slug']}.md"
        body_path.write_text(body)
        if key not in STATE:
            issue = api(API + '/issues', 'POST', {
                'title': title, 'body': body,
                'labels': ['ready-for-agent', f"priority:{ticket['priority']}"],
                'parent_issue_id': PARENT_BEFORE['id'],
            })
            STATE[key] = {'number': issue['number'], 'id': issue['id'], 'url': issue['html_url'],
                          'title': issue['title'], 'priority': ticket['priority'], 'dependencies': []}
            save()
        published = STATE[key]
        current = api(API + f"/issues/{published['number']}/dependencies/blocked_by")
        current_ids = {issue['id'] for issue in current}
        for blocker in ticket['blockers']:
            other = STATE[str(blocker)]
            if other['id'] not in current_ids:
                api(API + f"/issues/{published['number']}/dependencies/blocked_by", 'POST',
                    {'issue_id': other['id']})
            if blocker not in published['dependencies']:
                published['dependencies'].append(blocker)
                save()
        print(f"T{ticket['id']:02d} → #{published['number']}，{ticket['priority']}，"
              f"原生前置关系 {len(ticket['blockers'])} 条", flush=True)
    print(f'已发布并关联 {len(STATE)}/32 张任务', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--through', type=int, default=32)
    args = parser.parse_args()
    publish(args.through)
