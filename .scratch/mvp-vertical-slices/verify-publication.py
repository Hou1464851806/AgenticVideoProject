import json
import re
import subprocess
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = 'Hou1464851806/AgenticVideoProject'
API = f'repos/{REPO}'


def read(endpoint):
    result = subprocess.run(['gh', 'api', endpoint,
                             '-H', 'Accept: application/vnd.github+json',
                             '-H', 'X-GitHub-Api-Version: 2026-03-10'],
                            capture_output=True, text=True, timeout=55)
    if result.returncode:
        raise RuntimeError(f'{endpoint}: {result.stderr.strip()}')
    return json.loads(result.stdout)


tickets = json.loads((ROOT / 'tickets.json').read_text())
published = json.loads((ROOT / 'published.json').read_text())
assert len(published) == len(tickets) == 32
issues = {issue['number']: issue for issue in read(API + '/issues?state=all&per_page=100')}
children = read(API + '/issues/1/sub_issues?per_page=100')
assert {p['number'] for p in published.values()}.issubset({c['number'] for c in children})

before = json.loads(Path('/tmp/agentic-video-parent-before-publish.json').read_text())
parent = issues[1]
assert all(parent[field] == before[field] for field in ('title', 'body', 'state'))
assert {label['name'] for label in parent['labels']} == set(before['labels'])

for ticket in tickets:
    p = published[str(ticket['id'])]
    issue = issues[p['number']]
    assert issue['title'] == p['title']
    assert issue['state'] == 'open'
    assert {label['name'] for label in issue['labels']} == {'ready-for-agent', f"priority:{ticket['priority']}"}
    body = (ROOT / 'published' / f"{ticket['id']:02d}-{ticket['slug']}.md").read_text()
    assert issue['body'] == body
    assert body.count('- [ ] ') == len(ticket['acceptance']) == 4
    assert '#1' in body
    assert not re.search(r'\bT\d{2}\b', body)


def check_edges(ticket):
    p = published[str(ticket['id'])]
    edges = read(API + f"/issues/{p['number']}/dependencies/blocked_by")
    actual = {edge['id'] for edge in edges}
    expected = {published[str(b)]['id'] for b in ticket['blockers']}
    assert actual == expected, (ticket['id'], actual, expected)
    return len(edges)


with ThreadPoolExecutor(max_workers=4) as pool:
    edge_counts = list(pool.map(check_edges, tickets))
assert sum(edge_counts) == 49

summary = {
    'issues_verified': len(tickets),
    'native_sub_issues_verified': len(tickets),
    'native_dependency_edges_verified': sum(edge_counts),
    'priority_counts': dict(Counter(t['priority'] for t in tickets)),
    'parent_body_title_state_and_labels_unchanged': True,
    'body_and_four_acceptance_criteria_verified': True,
    'frontier': [published[str(t['id'])]['number'] for t in tickets if not t['blockers']],
}
(ROOT / 'verification.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')

graph = (ROOT / 'dependencies.mmd').read_text()
for ticket in tickets:
    p = published[str(ticket['id'])]
    graph = graph.replace(f'T{ticket["id"]:02d} ', f'#{p["number"]} ')
graph = re.sub(r'\bT(\d{2})\b', lambda m: 'I' + str(published[str(int(m.group(1)))]['number']), graph)
for ticket in tickets:
    p = published[str(ticket['id'])]
    graph += f'  click I{p["number"]} "{p["url"]}"\n'
(ROOT / 'published-dependencies.mmd').write_text(graph)

parent_url = f'https://github.com/{REPO}/issues/1'
lines = ['# GitHub Issues 发布结果', '',
         f'32 张任务已发布为 [总 Issue #1]({parent_url}) 的原生子 Issue，49 条直接阻塞关系均已回读验证。', '',
         'P0：11 张；P1：19 张；P2：2 张。每票有 4 项完成标准、验证方式和需求关联，标签均为 ready-for-agent 与对应 priority。', '',
         '总 Issue 的正文、标题、状态与标签未更改；子任务关系已添加。当前可立即开始的任务为下表 T01。', '',
         '| 草稿编号 | GitHub Issue | 优先级 | 前置 Issue |',
         '| --- | --- | --- | --- |']
for ticket in tickets:
    p = published[str(ticket['id'])]
    blockers = '、'.join(f"[#{published[str(b)]['number']}]({published[str(b)]['url']})" for b in ticket['blockers']) or '无'
    lines.append(f"| T{ticket['id']:02d} | [#{p['number']} {ticket['title']}]({p['url']}) | {ticket['priority']} | {blockers} |")
lines += ['', '```mermaid', graph.rstrip(), '```', '',
          '正式导出复用预览票完成的渲染能力；用户无需先预览。所有功能票均包含自身必要验证，最终验收票执行跨功能演练。', '']
(ROOT / 'published.md').write_text('\n'.join(lines))

# Replace draft-only status and references in the local review artifacts.
review_path = ROOT / 'README.md'
review = review_path.read_text()
review = review.replace('# GitHub Issues 拆分草稿', '# GitHub Issues 拆分与发布记录')
review = review.replace('状态：仅本地草稿，等待用户确认后发布；没有创建或修改 GitHub Issues。',
                        '状态：用户已确认，32 张任务已发布并完成回读验证。见 [发布映射与实际依赖图](published.md)。')
review = review.replace('T 编号是草稿编号；', 'T 编号是审阅时的草稿编号，真实 Issue 编号见发布映射；')
review = review.replace('## 确认后发布方式', '## 发布方式（已执行）')
review = review.replace('请确认任务粒度、直接阻塞关系，以及是否需要合并／拆分某些任务。确认前不会发布。',
                        '用户已确认任务粒度与依赖；32 个子 Issue 和 49 条原生阻塞关系已发布并验证。')
for ticket in tickets:
    p = published[str(ticket['id'])]
    review = review.replace(f"issues/{ticket['id']:02d}-{ticket['slug']}.md", p['url'])
    draft_path = ROOT / 'issues' / f"{ticket['id']:02d}-{ticket['slug']}.md"
    local = draft_path.read_text()
    local = local.replace('状态：待用户确认的本地草稿，尚未发布。T 编号仅用于审阅，不是 GitHub Issue 编号。',
                          f"状态：已发布为 [#{p['number']}]({p['url']})。T 编号保留为审阅映射。")
    local = local.replace('发布时使用', '标签为')
    for blocker in ticket['blockers']:
        other = published[str(blocker)]
        local = local.replace(f'- T{blocker:02d}：', f"- [#{other['number']}]({other['url']})：")
    final = published['31']
    local = local.replace('跨功能复验另由 T31 汇总', f"跨功能复验另由 [#{final['number']}]({final['url']}) 汇总")
    if ticket['id'] == 24:
        preview = published['23']
        local = local.replace('前置 T23', f"前置 [#{preview['number']}]({preview['url']})")
    draft_path.write_text(local)
review_path.write_text(review)
print(json.dumps(summary, ensure_ascii=False), flush=True)
