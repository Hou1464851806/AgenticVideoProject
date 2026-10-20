(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  const clone = value => (typeof structuredClone === 'function' ? structuredClone(value) : JSON.parse(JSON.stringify(value)));
  const icon = (name, cls = 'ic') => `<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const pad = value => String(value).padStart(2, '0');
  const formatTime = seconds => {
    const total = Math.max(0, Math.floor(seconds));
    return `${pad(Math.floor(total / 60))}:${pad(total % 60)}`;
  };
  const parseTime = value => {
    const match = /^(\d{1,3}):([0-5]\d)$/.exec(value || '');
    return match ? Number(match[1]) * 60 + Number(match[2]) : 0;
  };
  const formatBytes = bytes => {
    if (!bytes) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
    return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
  };
  let sequence = 0;
  const nextId = prefix => `${prefix}-${++sequence}`;

  const STAGE = './assets/stage.jpg';
  const RICH_SOURCE = '创始人访谈_林屿.mp4';
  const RICH_SEGMENTS = [
    { no: '01', out: '00:00–00:08', src: '03:12–03:20', text: '过去怎么剪', grow: 8 },
    { no: '02', out: '00:08–00:19', src: '07:41–07:52', text: '效率与时间', grow: 11 },
    { no: '03', out: '00:19–00:31', src: '09:05–09:17', text: '从素材到成片', grow: 12 },
    { no: '04', out: '00:31–00:45', src: '11:26–11:40', text: '把表达还给你', grow: 14 }
  ];

  function makeRichDraft(version = 'V2') {
    return {
      version,
      atIndex: 3,
      title: '预告 · 竖屏 45 秒',
      duration: '00:45',
      format: '1080 × 1920',
      clipCount: '4 段',
      subtitleCount: '8 条字幕',
      source: RICH_SOURCE,
      segments: clone(RICH_SEGMENTS),
      note: '保留原声与中文字幕；4 段硬切，结尾留 0.5 秒黑场。',
      collapsed: false,
      readOnly: false,
      justCreated: false
    };
  }

  function makePlan(version, draft) {
    return {
      version,
      title: draft.title,
      duration: draft.duration,
      format: draft.format,
      clipCount: draft.clipCount,
      subtitleCount: draft.subtitleCount,
      source: draft.source,
      segments: clone(draft.segments),
      spec: 'H.264 · AAC'
    };
  }

  function makeGenericDraft(asset, version = 'V1') {
    return {
      version,
      atIndex: 0,
      title: '剪辑草稿 · 竖屏 30 秒',
      duration: '00:30',
      format: '1080 × 1920',
      clipCount: '3 段',
      subtitleCount: '5 条字幕',
      source: asset?.name ?? '尚未选择素材',
      segments: [
        { no: '01', out: '00:00–00:08', src: '00:42–00:50', text: '先讲清楚问题', grow: 8 },
        { no: '02', out: '00:08–00:19', src: '01:16–01:27', text: '给出一个具体做法', grow: 11 },
        { no: '03', out: '00:19–00:30', src: '02:08–02:19', text: '落在结果上', grow: 11 }
      ],
      note: '保留原声与中文字幕；3 段硬切，结尾留 0.5 秒黑场。',
      collapsed: false,
      readOnly: false,
      justCreated: true
    };
  }

  function makeQuestion() {
    return {
      id: 'q1',
      prompt: '片尾署名用哪一个？',
      hint: '导出时写入片尾 2 秒',
      options: ['叙帧团队', '林屿', '不署名'],
      selected: '叙帧团队',
      custom: '',
      submitting: false
    };
  }

  function makeRichChat() {
    return {
      id: 'c1',
      projectId: 'p1',
      title: '45 秒竖屏预告',
      rich: true,
      messages: [
        { role: 'user', text: '用林屿的访谈做一条 45 秒的竖屏预告，保留原声和中文字幕。' },
        { role: 'assistant', text: '第一版 52 秒，偏慢。要压到 45 秒，需要拿掉第二段的一句过渡。' },
        { role: 'user', text: '压到 45 秒，结尾那句保留。' },
        { role: 'assistant', text: '已压到 45 秒，4 段都来自林屿的访谈，结尾落在「把表达还给你」。' }
      ],
      draft: makeRichDraft('V2'),
      question: makeQuestion(),
      plan: makePlan('V1', makeRichDraft('V1')),
      tasks: [{ id: 'task-v1-1080', kind: 'export', version: 'V1', profile: '1080p', progress: 62, status: 'running', fast: false, timer: null }],
      history: [{ id: 'history-v1-720', version: 'V1', profile: '720p', when: '昨天 16:12', state: 'done' }],
      references: ['a1'],
      input: '',
      generating: false,
      phase: '',
      genTimers: [],
      flashPlan: false
    };
  }

  function makeSimpleChat(id, projectId, title, welcome) {
    return {
      id,
      projectId,
      title,
      rich: false,
      messages: [{ role: 'assistant', text: welcome }],
      draft: null,
      question: null,
      plan: null,
      tasks: [],
      history: [],
      references: [],
      input: '',
      generating: false,
      phase: '',
      genTimers: [],
      flashPlan: false
    };
  }

  const p1Assets = [
    { id: 'a1', name: RICH_SOURCE, duration: '12:38', state: 'ready', stages: { media: '通过', speech: '就绪', vision: '就绪', index: '就绪' } },
    { id: 'a2', name: '主舞台_Keynote_4K.mp4', duration: '38:12', state: 'analyzing', stages: { media: '通过', speech: '就绪', vision: '分析中', index: '待构建' } },
    { id: 'a3', name: '用户证言_周女士.mp4', duration: '02:06', state: 'ready', stages: { media: '通过', speech: '就绪', vision: '就绪', index: '就绪' } },
    { id: 'a4', name: '产品演示_录屏.mp4', duration: '05:41', state: 'idle', stages: { media: '通过', speech: '未分析', vision: '未分析', index: '未构建' } },
    { id: 'a5', name: '后台花絮_A.mp4', duration: '01:06', state: 'uploading', stages: { media: '上传中', speech: '—', vision: '—', index: '—' }, upload: { progress: 62, paused: false, sizeText: '662 MB / 1.06 GB' } }
  ];

  const projects = [
    {
      id: 'p1',
      name: '叙帧 2.0 · 秋季发布',
      activeChat: 'c1',
      assets: p1Assets,
      chats: [
        makeRichChat(),
        makeSimpleChat('c2', 'p1', '开场 Keynote 金句', '这个会话先做开场金句。告诉我想让观众记住哪一句，我从主舞台素材里找。'),
        makeSimpleChat('c3', 'p1', '用户证言混剪', '这个会话可以做用户证言混剪。先告诉我想保留哪几位用户的原话。'),
        makeSimpleChat('c4', 'p1', '产品演示 30 秒', '这个会话做产品演示。想突出哪一个功能？我按 30 秒来剪。'),
        makeSimpleChat('c5', 'p1', '发布会花絮', '这个会话做花絮。可以先告诉我希望观众感受到什么情绪。')
      ]
    },
    {
      id: 'p2',
      name: '品牌年报 · 影像',
      activeChat: 'p2c1',
      assets: [
        { id: 'p2a1', name: '年度混剪_原片.mp4', duration: '08:20', state: 'ready', stages: { media: '通过', speech: '就绪', vision: '就绪', index: '就绪' } },
        { id: 'p2a2', name: '品牌片头_动画.mp4', duration: '00:15', state: 'analyzing', stages: { media: '通过', speech: '就绪', vision: '分析中', index: '待构建' } }
      ],
      chats: [
        makeSimpleChat('p2c1', 'p2', '年度回顾 60 秒', '这个项目有 2 份素材。告诉我想讲哪三个业务节点，我来出草稿。'),
        makeSimpleChat('p2c2', 'p2', '品牌片头 15 秒', '片头可以单独做一条。先给我一个片长和一句主张。')
      ]
    },
    {
      id: 'p3',
      name: '用户故事 · Q4',
      activeChat: 'p3c1',
      assets: [],
      chats: [
        makeSimpleChat('p3c1', 'p3', '用户故事 · 林女士', '这个项目还没有素材。先在素材库上传一段视频，再开始剪辑。')
      ]
    }
  ];

  const ui = {
    activeProject: 'p1',
    follow: true,
    hasNew: false,
    drawerOpen: false,
    drawerTrigger: null,
    selectedAsset: null,
    query: '',
    filter: 'all',
    newUpload: null,
    uploadInterval: null,
    uploadTimers: [],
    player: null,
    playerTimer: null,
    toastTimer: null,
    panelCollapsed: false,
    leftCollapsed: false
  };

  const el = {
    rail: $('#rail'), railInner: $('#railInner'), railCompact: $('#railCompact'),
    projectList: $('#projectList'), projectCount: $('#projectCount'),
    assetCount: $('#assetCount'), assetSummary: $('#assetSummary'),
    chatTitle: $('#chatTitle'), chatState: $('#chatState'), chatStateText: $('#chatStateText'),
    thread: $('#thread'), threadScroll: $('#threadScroll'), jumpLatest: $('#jumpLatest'),
    runStatus: $('#runStatus'), runText: $('#runText'),
    composerForm: $('#composerForm'), composerInput: $('#composerInput'), sendBtn: $('#sendBtn'), ctxChips: $('#ctxChips'),
    artifacts: $('#artifacts'), artifactsRail: $('#artifactsRail'), artifactBody: $('#artifactBody'), artifactCount: $('#artifactCount'), panelToggle: $('#panelToggle'),
    drawer: $('#drawer'), drawerClose: $('#drawerClose'), drawerCount: $('#drawerCount'), scrim: $('#scrim'),
    dropzone: $('#dropzone'), fileInput: $('#fileInput'), assetSearch: $('#assetSearch'), assetFilters: $('#assetFilters'), assetList: $('#assetList'), assetDetail: $('#assetDetail'),
    toast: $('#toast'),
    playerVeil: $('#playerVeil'), playerDialog: $('#playerStage')?.closest('.player-dialog'),
    playerImage: $('#playerImage'), playerPlaceholder: $('#playerPlaceholder'), playerBadge: $('#playerBadge'),
    playerBigPlay: $('#playerBigPlay'), playerPlayBtn: $('#playerPlayBtn'), playerTime: $('#playerTime'), playerDuration: $('#playerDuration'), playerProgress: $('#playerProgress'),
    playerTitle: $('#playerTitle'), playerMeta: $('#playerMeta'), playerSource: $('#playerSource'), playerKind: $('#playerKind'), playerNote: $('#playerNote'),
    playerClose: $('.player-close'), playerDownload: $('#playerDownload')
  };

  function currentProject() { return projects.find(project => project.id === ui.activeProject); }
  function currentChat() {
    const project = currentProject();
    return project.chats.find(chat => chat.id === project.activeChat) || project.chats[0];
  }
  function projectOf(chat) { return projects.find(project => project.id === chat.projectId); }
  function assetById(id) { return currentProject().assets.find(asset => asset.id === id); }
  function hasRunningExport(chat, version = null) {
    return chat.tasks.some(task => task.kind === 'export' && task.status === 'running' && (version === null || task.version === version));
  }
  function chatBadge(chat) {
    if (chat.generating) return { text: '生成中', run: true };
    if (chat.draft && !chat.draft.readOnly) return { text: '待确认', run: false };
    if (chat.tasks.some(task => task.kind === 'export' && task.status === 'running')) return { text: '导出中', run: true };
    if (chat.tasks.some(task => task.kind === 'preview' && task.status === 'running')) return { text: '预览中', run: true };
    return null;
  }
  function bestExport(chat, version) {
    const done = chat.history.filter(item => item.version === version && item.state === 'done');
    if (!done.length) return null;
    return done.some(item => item.profile === '1080p') ? '1080p' : '720p';
  }
  function nextVersion(chat) {
    const version = chat.draft?.version || chat.plan?.version || 'V0';
    const number = Number(/([0-9]+)/.exec(version)?.[1] || 0);
    return `V${number + 1}`;
  }
  function assetStateText(asset) {
    if (asset.state === 'uploading') {
      const upload = asset.upload;
      if (!upload) return '上传中';
      return upload.paused ? `已暂停 · ${upload.progress}%` : `上传中 ${upload.progress}%`;
    }
    if (asset.state === 'analyzing') return `画面分析中${asset.stages.speech === '就绪' ? ' · 语音就绪' : ''}`;
    if (asset.state === 'idle') return '待分析';
    const speech = asset.stages.speech === '就绪' ? '语音就绪' : '语音未分析';
    const vision = asset.stages.vision === '就绪' ? '画面就绪' : '画面未分析';
    return `${speech} · ${vision}`;
  }
  function assetMeta(asset) {
    const duration = asset.duration && asset.duration !== '—' ? ` · ${asset.duration}` : '';
    return `${assetStateText(asset)}${duration}`;
  }
  function canPlayAsset(asset) { return asset.state === 'ready' || asset.state === 'analyzing'; }

  /* ---------------- navigation ---------------- */

  function renderNav() {
    el.projectCount.textContent = String(projects.length);
    el.projectList.innerHTML = projects.map(project => {
      const open = project.id === ui.activeProject;
      const chats = open ? `<ul class="chats">${project.chats.map(chat => {
        const badge = chatBadge(chat);
        return `<li><button class="chat-btn ${chat.id === project.activeChat ? 'active' : ''}" data-act="select-chat" data-id="${chat.id}">
          <span class="t">${esc(chat.title)}</span>${badge ? `<span class="mini-badge ${badge.run ? 'run' : ''}">${badge.text}</span>` : ''}
        </button></li>`;
      }).join('')}${project.chats.length ? '' : '<li><span class="asset-empty">还没有聊天</span></li>'}</ul>` : '';
      return `<button class="project ${open ? 'open' : ''}" data-act="select-project" data-id="${project.id}">
        ${icon('folder', 'ic ic-sm')}<span class="name">${esc(project.name)}</span><span class="count">${project.chats.length}</span>${icon('chev', 'ic ic-sm chev')}
      </button>${chats}`;
    }).join('');
  }

  function renderAssetSummary() {
    const project = currentProject();
    const chat = currentChat();
    const upload = ui.newUpload && ui.newUpload.projectId === project.id ? ui.newUpload : null;
    const total = project.assets.length + (upload ? 1 : 0);
    el.assetCount.textContent = String(total);
    if (!total) {
      el.assetSummary.innerHTML = '<p class="asset-empty">还没有素材。上传一段视频开始。</p>';
      return;
    }
    const score = asset => (chat.references.includes(asset.id) ? 4 : 0) + (asset.state === 'uploading' ? 3 : 0) + (asset.state === 'analyzing' ? 2 : 0) + (asset.state === 'ready' ? 1 : 0);
    const picks = [...project.assets].sort((a, b) => score(b) - score(a)).slice(0, upload ? 2 : 3);
    const rows = [];
    if (upload) {
      rows.push(`<button class="asset-row" data-act="open-drawer" data-focus="upload">
        <span class="file-chip">${icon('film', 'ic ic-sm')}</span>
        <span class="asset-main"><span class="asset-name">${esc(upload.name)}</span><span class="asset-sub">${esc(uploadStatusText(upload))}</span><span class="pbar"><i style="width:${upload.progress}%"></i></span></span>
      </button>`);
    }
    for (const asset of picks) {
      rows.push(`<button class="asset-row ${chat.references.includes(asset.id) ? 'ref' : ''}" data-act="open-asset" data-id="${asset.id}">
        <span class="file-chip">${icon('film', 'ic ic-sm')}</span>
        <span class="asset-main"><span class="asset-name">${esc(asset.name)}</span><span class="asset-sub">${esc(assetMeta(asset))}</span>${asset.state === 'uploading' ? `<span class="pbar"><i style="width:${asset.upload?.progress ?? 0}%"></i></span>` : ''}</span>
        ${canPlayAsset(asset) ? `<span class="asset-play" aria-hidden="true">${icon('play', 'ic ic-sm')}</span>` : ''}
      </button>`);
    }
    el.assetSummary.innerHTML = rows.join('');
  }

  function uploadStatusText(upload) {
    if (upload.status === 'validating') return '上传完成，媒体验证中…';
    if (upload.status === 'analyzing') return '媒体验证通过，语音分析中…';
    if (upload.status === 'paused') return `已暂停 · ${Math.round(upload.progress)}%`;
    return `上传中 ${Math.round(upload.progress)}%`;
  }

  /* ---------------- header ---------------- */

  function renderHeader() {
    const chat = currentChat();
    el.chatTitle.textContent = chat.title;
    let text = '就绪', cls = '';
    if (chat.generating) { text = '生成中'; cls = 'is-running'; }
    else if (chat.draft && !chat.draft.readOnly) { text = '草稿待确认'; }
    else if (chat.plan) { text = '已确认'; cls = 'is-done'; }
    el.chatState.className = `state ${cls}`;
    el.chatStateText.textContent = text;
    el.panelToggle.setAttribute('aria-expanded', String(!ui.panelCollapsed));
    el.panelToggle.setAttribute('aria-label', ui.panelCollapsed ? '展开产物面板' : '收起产物面板');
  }

  /* ---------------- messages & draft ---------------- */

  function renderMessages() {
    const chat = currentChat();
    let html = '';
    if (chat.rich || chat.messages.length > 4) html += '<button class="older">12 条更早消息</button>';
    chat.messages.forEach((message, index) => {
      if (message.role === 'user') html += `<div class="msg-user"><div class="bubble">${esc(message.text)}</div></div>`;
      else if (message.role === 'system') html += `<div class="msg-system">${esc(message.text)}</div>`;
      else html += `<article class="msg-assistant"><p>${esc(message.text)}</p></article>`;
      if (chat.draft && chat.draft.atIndex === index) html += renderDraft(chat);
    });
    if (chat.draft && (!Number.isInteger(chat.draft.atIndex) || chat.draft.atIndex >= chat.messages.length)) html += renderDraft(chat);
    if (chat.question) html += renderQuestion(chat);
    const project = projectOf(chat);
    if (!project.assets.length && chat.messages.length <= 1 && !chat.draft && !chat.plan) html += `<div class="start-actions"><button class="btn primary btn-sm" data-act="open-drawer" data-focus="upload">${icon('upload', 'ic ic-sm')}上传素材</button></div>`;
    if (chat.generating) html += `<div class="thinking"><span class="spin" aria-hidden="true"></span><span>${esc(chat.phase || '正在组织答复')}</span></div>`;
    el.thread.innerHTML = html;
    el.thread.classList.toggle('is-start', chat.messages.length <= 1 && !chat.draft && !chat.plan && !chat.question);
    if (chat.draft) chat.draft.justCreated = false;
    if (ui.follow) scrollToBottom();
    updateJump();
  }

  function renderDraft(chat) {
    const draft = chat.draft;
    if (!draft) return '';
    if (draft.collapsed && !draft.readOnly) {
      return `<div class="draft-collapsed" id="draftCard" tabindex="-1"><span class="kicker">${icon('film', 'ic ic-sm')}剪辑草稿 ${esc(draft.version)} 已收起</span><span class="t">${esc(draft.title)}</span><button class="link-btn" data-act="expand-draft">查看草稿</button></div>`;
    }
    const disabled = chat.generating ? ' disabled' : '';
    return `<article class="draft ${draft.justCreated ? 'just-created' : ''}" id="draftCard" tabindex="-1" aria-label="剪辑草稿">
      <div class="draft-head">
        <span class="kicker">${icon('film', 'ic ic-sm')}剪辑草稿</span>
        <span class="chip ${draft.readOnly ? 'is-confirmed' : ''}"><i></i>${draft.readOnly ? '已确认' : '待确认'}</span>
        <span class="ver">${esc(draft.version)}</span>
      </div>
      <h2>${esc(draft.title)}</h2>
      <div class="spec"><span>${esc(draft.duration)}</span><span>${esc(draft.format)}</span><span>${esc(draft.clipCount)}</span><span>${esc(draft.subtitleCount)}</span></div>
      <div class="seg-source">${icon('film', 'ic ic-sm')}来源 · ${esc(draft.source)}</div>
      <div class="segments">${draft.segments.map((segment, index) => `<div class="seg ${index === draft.segments.length - 1 ? 'ref' : ''}" style="flex-grow:${segment.grow}"><div class="seg-top"><b>${esc(segment.no)}</b><span>${esc(segment.out)}</span></div><p>${esc(segment.text)}</p></div>`).join('')}</div>
      <p class="draft-note">${esc(draft.note)}</p>
      ${draft.readOnly
        ? `<div class="draft-readonly">${icon('check', 'ic ic-sm')}<span>方案 ${esc(draft.version)} 已确认</span><span class="grow"></span><button class="btn btn-sm" data-act="focus-artifacts">在产物中查看</button></div>`
        : `<div class="draft-foot">
            <button class="link-btn" data-act="dismiss-draft" title="草稿会保留，可稍后回来确认">暂不采纳</button>
            <span class="hint">${chat.generating ? '正在生成新版本，稍候…' : '确认后草稿只读，正式导出需另行操作'}</span>
            <div class="draft-actions">
              <button class="btn" data-act="preview-draft"${disabled}>预览低清版</button>
              <button class="btn primary" data-act="confirm-draft"${disabled}>确认全部输出</button>
            </div>
          </div>`}
    </article>`;
  }

  function renderQuestion(chat) {
    const question = chat.question;
    const submitDisabled = question.submitting || (!question.selected && !question.custom.trim());
    return `<section class="question" aria-label="待回答问题">
      <div class="q-head"><span class="q-mark">?</span><strong>${esc(question.prompt)}</strong><span class="q-hint">${esc(question.hint)}</span></div>
      <div class="q-opts">${question.options.map(option => `<button class="q-opt ${question.selected === option ? 'on' : ''}" data-act="answer-option" data-value="${esc(option)}"${question.submitting ? ' disabled' : ''}>${esc(option)}</button>`).join('')}</div>
      <div class="q-input">
        <input id="questionCustom" aria-label="其他署名" maxlength="80" placeholder="其他署名…" value="${esc(question.custom)}"${question.submitting ? ' disabled' : ''}>
        <button class="q-submit" data-act="submit-answer" aria-label="提交回答"${submitDisabled ? ' disabled' : ''}>${question.submitting ? icon('check', 'ic ic-sm') : icon('arrow', 'ic ic-sm')}</button>
      </div>
    </section>`;
  }

  /* ---------------- composer ---------------- */

  function renderComposer() {
    const chat = currentChat();
    const refs = chat.references.map(id => assetById(id)).filter(Boolean);
    el.ctxChips.innerHTML = refs.map(asset => `<span class="ctx-chip">${icon('film', 'ic ic-sm')}<b>${esc(asset.name)}</b><button type="button" data-act="remove-ref" data-id="${asset.id}" aria-label="移除素材引用">${icon('x', 'ic ic-sm')}</button></span>`).join('');
    if (el.composerInput.value !== chat.input) el.composerInput.value = chat.input;
    el.sendBtn.disabled = !chat.input.trim() || chat.generating;
    el.runStatus.hidden = !chat.generating;
    el.runText.textContent = chat.phase || '正在组织答复';
    autoGrow();
  }

  function autoGrow() {
    el.composerInput.style.height = '42px';
    el.composerInput.style.height = `${Math.min(132, Math.max(42, el.composerInput.scrollHeight))}px`;
  }

  function scrollToBottom() {
    el.threadScroll.scrollTop = el.threadScroll.scrollHeight;
    ui.follow = true;
    ui.hasNew = false;
    updateJump();
  }

  function updateJump() {
    if (!el.jumpLatest) return;
    el.jumpLatest.hidden = !(ui.hasNew && !ui.follow);
    el.jumpLatest.classList.toggle('is-visible', !!(ui.hasNew && !ui.follow));
  }

  /* ---------------- artifacts ---------------- */

  function renderArtifacts() {
    const chat = currentChat();
    const plan = chat.plan;
    const runningExports = chat.tasks.filter(task => task.kind === 'export' && task.status === 'running');
    const previews = chat.tasks.filter(task => task.kind === 'preview');
    const preview = previews.length ? previews[previews.length - 1] : null;
    let html = '';

    if (plan) {
      const playback = bestExport(chat, plan.version);
      html += `<div class="eyebrow">已确认方案<span class="grow"></span></div>
        <article class="plan-card${chat.flashPlan ? ' is-new' : ''}" aria-label="已确认方案">
          <div class="plan-top"><span class="ver" style="margin:0">${esc(plan.version)}</span><span class="status"><i></i>已确认</span></div>
          <div class="plan-main">
            <div class="poster"><img src="${STAGE}" alt="预告片画面"><span class="time">${esc(plan.duration)}</span>${playback ? `<button class="play" data-act="play-plan" data-profile="${playback}" data-version="${esc(plan.version)}" aria-label="播放 ${playback} 成片">${icon('play', 'ic ic-sm')}</button>` : `<span class="play">${icon('play', 'ic ic-sm')}</span>`}</div>
            <div class="plan-info">
              <h3 id="planHeading" tabindex="-1">${esc(plan.title)}</h3>
              <p>${esc(plan.duration)} · ${esc(plan.format)}</p>
              <p>${esc(plan.clipCount)} · ${esc(plan.subtitleCount)}</p>
              <p class="src">${esc(plan.source)}</p>
            </div>
          </div>
          <div class="plan-actions">
            <button class="btn" data-act="preview-plan" data-version="${esc(plan.version)}">预览低清</button>
            ${playback ? `<button class="btn" data-act="play-plan" data-profile="${playback}" data-version="${esc(plan.version)}">播放 ${playback}</button>` : runningExports.some(task => task.version === plan.version) ? '<button class="btn" disabled>导出中…</button>' : '<button class="btn" disabled>尚未导出</button>'}
          </div>
        </article>
        <div class="eyebrow">方案片段<span class="grow"></span><span style="font-family:var(--mono);font-size:9.5px;color:var(--ink-4)">${plan.segments.length}</span></div>
        <div class="clips">${plan.segments.map(segment => `<div class="clip-row"><span class="cno">${esc(segment.no)}</span><span class="ctime">${esc(segment.out)}</span><span class="ctext">${esc(segment.text)}</span></div>`).join('')}</div>`;
    }

    if (preview) {
      html += `<div class="eyebrow">低清预览<span class="grow"></span>${preview.status === 'running' ? '<span class="live">合成中</span>' : ''}</div>`;
      if (preview.status === 'running') {
        html += `<article class="task" data-task-id="${preview.id}">
          <div class="task-top"><span class="spin" aria-hidden="true"></span><strong>${esc(preview.version)} · 低清预览</strong><span class="pct">${preview.progress}%</span></div>
          <p>${esc(preview.title)} · ${esc(preview.version)} · 1080 × 1920</p>
          <div class="bar"><i style="width:${preview.progress}%"></i></div>
          <div class="task-foot"><span>正在合成画面与字幕</span><button data-act="stop-task" data-id="${preview.id}">停止预览</button></div>
        </article>`;
      } else if (preview.status === 'done') {
        html += `<div class="preview-card"><div class="preview-top"><strong>低清预览 · ${esc(preview.version)} 已就绪</strong></div><p>${esc(preview.title)}</p><div class="preview-foot"><span class="grow"></span><button class="btn btn-sm" data-act="play-preview" data-id="${preview.id}">播放预览</button></div></div>`;
      } else {
        html += `<div class="preview-card"><div class="preview-top"><strong>低清预览已停止</strong></div><p>${esc(preview.title)}</p><div class="preview-foot"><span class="grow"></span><button class="btn btn-sm" data-act="retry-preview">重新生成</button></div></div>`;
      }
    }

    if (runningExports.length) {
      html += `<div class="eyebrow">渲染任务<span class="grow"></span><span class="live">${runningExports.length} 运行中</span></div>`;
      for (const task of runningExports) {
        html += `<article class="task" data-task-id="${task.id}">
          <div class="task-top"><span class="spin" aria-hidden="true"></span><strong>${esc(task.version)} · 正式导出 · ${task.profile}</strong><span class="pct">${task.progress}%</span></div>
          <p>${esc(task.version)} · 输出 1 · H.264 / AAC</p>
          <div class="bar"><i style="width:${task.progress}%"></i></div>
          <div class="task-foot"><span>正在导出，可离开本页</span><button data-act="stop-task" data-id="${task.id}">停止导出</button></div>
        </article>`;
      }
    }

    if (plan && !runningExports.some(task => task.version === plan.version)) {
      const playback = bestExport(chat, plan.version);
      html += `<div class="eyebrow">导出成片<span class="grow"></span></div>
        <div class="pending-export">
          <div class="pe-top"><strong>选择清晰度</strong><select id="exportProfile" aria-label="导出清晰度"><option value="1080p">1080p</option><option value="720p">720p</option></select></div>
          <div class="pe-foot"><span>每次导出一条输出${playback ? ` · 已完成 ${playback}` : ''}</span><button class="btn primary btn-sm" data-act="start-export">导出成片</button></div>
        </div>`;
    }

    if (chat.history.length) {
      html += `<div class="eyebrow">导出记录<span class="grow"></span></div>`;
      for (const item of chat.history) {
        const done = item.state === 'done';
        html += `<div class="history-row">
          <span class="prof">${esc(item.profile)}</span><span class="ver">${esc(item.version)}</span>
          <span class="when">${esc(item.when)}</span>
          <span class="${done ? 'ok' : 'stop-state'}">${done ? '<i></i>已完成' : '已停止'}</span>
          ${done ? `<button class="icon-btn" data-act="play-history" data-profile="${item.profile}" data-version="${item.version}" aria-label="播放 ${item.profile} 成片">${icon('play', 'ic ic-sm')}</button><button class="icon-btn" data-act="download" data-profile="${item.profile}" data-version="${item.version}" aria-label="下载 ${item.profile} 成片">${icon('download', 'ic ic-sm')}</button>` : ''}
        </div>`;
      }
    }

    if (!plan && !preview && !runningExports.length && !chat.history.length) {
      html += `<div class="empty-artifact"><div class="empty-icon">${icon('film')}</div><h3>还没有已确认方案</h3><p>在对话里确认草稿后，方案和渲染任务会出现在这里。</p>${chat.draft ? '<button class="btn btn-sm" data-act="focus-draft">查看当前草稿</button>' : ''}</div>`;
    } else if (!plan) {
      html += '<p class="pane-note">确认草稿后，方案会出现在这里；预览任务不受影响。</p>';
    } else {
      html += '<p class="pane-note">产物来自已确认版本；切换会话不会改变正在导出的来源。</p>';
    }

    const previousScroll = el.artifactBody.scrollTop;
    el.artifactBody.innerHTML = html;
    if (previousScroll > 0) el.artifactBody.scrollTop = previousScroll;
    const count = (plan ? 1 : 0) + runningExports.length + (preview ? 1 : 0);
    el.artifactCount.textContent = String(count);
    if (chat.flashPlan) chat.flashPlan = false;
  }

  /* ---------------- drawer ---------------- */

  function filteredAssets() {
    const project = currentProject();
    let assets = project.assets;
    if (ui.filter === 'ready') assets = assets.filter(asset => asset.state === 'ready');
    else if (ui.filter === 'processing') assets = assets.filter(asset => asset.state !== 'ready');
    const query = ui.query.trim().toLowerCase();
    if (query) assets = assets.filter(asset => asset.name.toLowerCase().includes(query));
    return assets;
  }

  function renderDrawer() {
    const project = currentProject();
    const upload = ui.newUpload && ui.newUpload.projectId === project.id ? ui.newUpload : null;
    const total = project.assets.length + (upload ? 1 : 0);
    const readyCount = project.assets.filter(asset => asset.state === 'ready').length + (upload ? 0 : 0);
    const processingCount = total - readyCount;
    el.drawerCount.textContent = String(total);
    el.assetFilters.innerHTML = `
      <button class="filter ${ui.filter === 'all' ? 'on' : ''}" data-act="filter-assets" data-filter="all">全部 ${total}</button>
      <button class="filter ${ui.filter === 'ready' ? 'on' : ''}" data-act="filter-assets" data-filter="ready">已就绪 ${readyCount}</button>
      <button class="filter ${ui.filter === 'processing' ? 'on' : ''}" data-act="filter-assets" data-filter="processing">处理中 ${processingCount}</button>`;
    if (document.activeElement !== el.assetSearch && el.assetSearch.value !== ui.query) el.assetSearch.value = ui.query;

    let list = '';
    if (upload) {
      const pct = Math.round(upload.progress);
      const paused = upload.status === 'paused';
      list += `<div class="drawer-asset">
        <span class="file-chip">${icon('film', 'ic ic-sm')}</span>
        <span class="asset-main">
          <span class="asset-name">${esc(upload.name)}</span>
          <span class="asset-sub">${esc(uploadStatusText(upload))}</span>
          <div class="asset-progress"><i id="uploadBar" style="width:${pct}%"></i></div>
          <span class="upload-meta">${esc(upload.sizeText)}</span>
        </span>
        <span class="side">
          ${upload.status === 'uploading' || upload.status === 'paused' ? `<button class="side-btn" data-act="${paused ? 'resume-upload' : 'pause-upload'}" aria-label="${paused ? '继续上传' : '暂停上传'}">${paused ? icon('play', 'ic ic-sm') : icon('pause', 'ic ic-sm')}</button>` : ''}
          <button class="side-btn" data-act="cancel-upload" aria-label="取消上传">${icon('x', 'ic ic-sm')}</button>
        </span>
      </div>`;
    }
    const assets = filteredAssets();
    for (const asset of assets) {
      const selected = ui.selectedAsset === asset.id;
      list += `<div class="drawer-asset ${selected ? 'is-selected' : ''}" data-act="select-asset" data-id="${asset.id}" tabindex="0" role="button" aria-label="素材：${esc(asset.name)}">
        <span class="file-chip">${icon('film', 'ic ic-sm')}</span>
        <span class="asset-main">
          <span class="asset-name">${esc(asset.name)}</span>
          <span class="asset-sub">${esc(assetMeta(asset))}</span>
          ${asset.state === 'uploading' ? `<div class="asset-progress"><i style="width:${asset.upload?.progress ?? 0}%"></i></div><span class="upload-meta">${esc(asset.upload?.sizeText ?? '')}</span>` : ''}
        </span>
        <span class="side">
          ${canPlayAsset(asset) ? `<button class="side-btn" data-act="play-asset" data-id="${asset.id}" aria-label="播放 ${esc(asset.name)}">${icon('play', 'ic ic-sm')}</button>` : asset.state === 'analyzing' ? `<span class="spin" aria-hidden="true"></span>` : ''}
          ${asset.state === 'uploading' ? `<button class="side-btn" data-act="toggle-existing-upload" data-id="${asset.id}" aria-label="${asset.upload?.paused ? '继续上传' : '暂停上传'}">${asset.upload?.paused ? icon('play', 'ic ic-sm') : icon('pause', 'ic ic-sm')}</button><button class="side-btn" data-act="cancel-existing-upload" data-id="${asset.id}" aria-label="取消上传">${icon('x', 'ic ic-sm')}</button>` : ''}
        </span>
      </div>`;
    }
    if (!upload && !assets.length) list = '<p class="asset-empty-state">还没有匹配的素材。换一个词，或上传一段视频。</p>';
    el.assetList.innerHTML = list;
    renderAssetDetail();
  }

  function renderAssetDetail() {
    const project = currentProject();
    const asset = project.assets.find(item => item.id === ui.selectedAsset);
    if (!asset) { el.assetDetail.innerHTML = ''; return; }
    const stages = [
      ['媒体', asset.stages.media],
      ['语音', asset.stages.speech],
      ['画面', asset.stages.vision],
      ['索引', asset.stages.index]
    ];
    const stageHtml = stages.map(([label, value]) => {
      const cls = value === '就绪' || value === '通过' ? 'ok' : value === '分析中' || value === '上传中' ? 'wait' : 'idle';
      return `<div class="stage-row"><span>${label}</span><b class="${cls}">${esc(value || '—')}</b></div>`;
    }).join('');
    const canAnalyze = asset.state !== 'uploading' && asset.stages.vision !== '就绪' && asset.stages.vision !== '分析中';
    const actions = [
      canPlayAsset(asset) ? `<button class="btn" data-act="play-asset" data-id="${asset.id}">${icon('play', 'ic ic-sm')}播放原片</button>` : '',
      canAnalyze ? `<button class="btn" data-act="analyze-asset" data-id="${asset.id}">分析画面</button>` : asset.stages.vision === '分析中' ? '<button class="btn" disabled><span class="spin" aria-hidden="true"></span>画面分析中</button>' : '',
      `<button class="btn" data-act="cite-asset" data-id="${asset.id}">${icon('plus', 'ic ic-sm')}引用到对话</button>`
    ].filter(Boolean).join('');
    el.assetDetail.innerHTML = `<div class="asset-detail">
      <h3>${esc(asset.name)}</h3>
      <p class="meta">${esc(assetStateText(asset))}${asset.duration !== '—' ? ` · ${esc(asset.duration)}` : ''}</p>
      <div class="stages">${stageHtml}</div>
      <div class="detail-actions">${actions}</div>
    </div>`;
  }

  /* ---------------- actions ---------------- */

  function setProject(id) {
    const project = projects.find(item => item.id === id);
    if (!project) return;
    ui.activeProject = id;
    ui.selectedAsset = null;
    ui.query = '';
    ui.filter = 'all';
    ui.follow = true;
    ui.hasNew = false;
    if (!project.chats.some(chat => chat.id === project.activeChat)) project.activeChat = project.chats[0]?.id;
    renderAll();
    el.artifactBody.scrollTop = 0;
    scrollToBottom();
    if (ui.drawerOpen) closeDrawer(false);
  }

  function setChat(id) {
    const project = currentProject();
    if (!project.chats.some(chat => chat.id === id)) return;
    project.activeChat = id;
    ui.selectedAsset = null;
    ui.query = '';
    ui.filter = 'all';
    ui.follow = true;
    ui.hasNew = false;
    renderAll();
    el.artifactBody.scrollTop = 0;
    scrollToBottom();
    if (ui.drawerOpen) closeDrawer(false);
  }

  function newChat() {
    const project = currentProject();
    const title = `新聊天 ${project.chats.length + 1}`;
    const chat = makeSimpleChat(nextId('chat'), project.id, title, '你好，我是你的剪辑助手。先告诉我这条片子想讲什么、用哪些素材，我来出草稿。');
    project.chats.push(chat);
    project.activeChat = chat.id;
    ui.selectedAsset = null;
    ui.query = '';
    ui.filter = 'all';
    ui.follow = true;
    ui.hasNew = false;
    renderAll();
    el.artifactBody.scrollTop = 0;
    el.composerInput.focus();
    scrollToBottom();
  }

  function setPanel(collapsed, focus = true) {
    ui.panelCollapsed = collapsed;
    el.artifacts.classList.toggle('is-collapsed', collapsed);
    document.querySelectorAll('[data-act="toggle-panel"]').forEach(button => button.setAttribute('aria-expanded', String(!collapsed)));
    renderHeader();
    if (focus) requestAnimationFrame(() => {
      if (collapsed) el.artifactsRail.focus();
      else $('.pane-head [data-act="toggle-panel"]')?.focus();
    });
  }

  function setLeft(collapsed, focus = true) {
    ui.leftCollapsed = collapsed;
    el.rail.classList.toggle('is-collapsed', collapsed);
    document.querySelectorAll('[data-act="toggle-left"]').forEach(button => button.setAttribute('aria-expanded', String(!collapsed)));
    if (focus) requestAnimationFrame(() => {
      if (collapsed) $('.rail-compact [data-act="toggle-left"]')?.focus();
      else $('.brand [data-act="toggle-left"]')?.focus();
    });
  }

  function openDrawer(focusTarget) {
    if (ui.drawerOpen) {
      if (focusTarget === 'upload') el.dropzone.focus();
      return;
    }
    ui.drawerTrigger = document.activeElement;
    ui.drawerOpen = true;
    el.drawer.inert = false;
    el.drawer.classList.add('is-open');
    el.scrim.classList.add('is-open');
    renderDrawer();
    requestAnimationFrame(() => {
      if (focusTarget === 'upload') el.dropzone.focus();
      else el.drawerClose.focus();
    });
  }

  function closeDrawer(restore = true) {
    if (!ui.drawerOpen) return;
    const trigger = ui.drawerTrigger;
    ui.drawerOpen = false;
    el.drawer.classList.remove('is-open');
    el.scrim.classList.remove('is-open');
    el.drawer.inert = true;
    ui.drawerTrigger = null;
    if (restore) requestAnimationFrame(() => {
      if (trigger && document.contains(trigger) && !el.drawer.contains(trigger)) trigger.focus();
      else el.composerInput.focus();
    });
  }

  function openAsset(id) {
    ui.selectedAsset = id;
    openDrawer();
    requestAnimationFrame(() => {
      document.querySelector('.drawer-asset.is-selected')?.focus();
      renderAssetDetail();
    });
  }

  function renderAll() {
    renderNav();
    renderAssetSummary();
    renderHeader();
    renderMessages();
    renderComposer();
    renderArtifacts();
    if (ui.drawerOpen) renderDrawer();
  }

  /* ---------------- generation ---------------- */

  function sendMessage() {
    const chat = currentChat();
    const text = el.composerInput.value.trim();
    if (!text || chat.generating) return;
    chat.messages.push({ role: 'user', text });
    chat.input = '';
    chat.generating = true;
    chat.phase = '正在理解你的修改';
    chat.genTimers.forEach(clearTimeout);
    chat.genTimers = [];
    ui.follow = true;
    ui.hasNew = false;
    renderMessages();
    renderComposer();
    renderHeader();
    renderNav();
    scrollToBottom();
    el.composerInput.focus();
    chat.genTimers.push(setTimeout(() => {
      if (!chat.generating) return;
      chat.phase = '正在核对素材与时长';
      if (currentChat() === chat) el.runText.textContent = chat.phase;
    }, 650));
    chat.genTimers.push(setTimeout(() => finishGeneration(chat), 1500));
  }

  function finishGeneration(chat) {
    if (!chat.generating) return;
    chat.genTimers.forEach(clearTimeout);
    chat.genTimers = [];
    chat.generating = false;
    chat.phase = '';
    const project = projectOf(chat);
    const usable = project.assets.find(asset => asset.state === 'ready') || project.assets.find(asset => asset.state !== 'uploading');
    if (chat.rich) {
      if (chat.draft && !chat.draft.readOnly) {
        chat.draft.version = nextVersion(chat);
        chat.draft.segments[3].text = '把时间还给你';
        chat.draft.note = '保留原声与中文字幕；结尾收紧到「把时间还给你」，时长不变。';
        chat.draft.atIndex = chat.messages.length;
        chat.draft.justCreated = true;
        chat.messages.push({ role: 'assistant', text: '收到。我把结尾收紧到「把时间还给你」，其余 3 段不动，时长仍是 45 秒。' });
        chat.draft.atIndex = chat.messages.length - 1;
      } else {
        const draft = makeRichDraft(nextVersion(chat));
        draft.segments[3].text = '把时间还给你';
        draft.atIndex = chat.messages.length;
        chat.messages.push({ role: 'assistant', text: '按你的新意见另起一版，之前的版本仍保留在产物里。' });
        draft.atIndex = chat.messages.length - 1;
        draft.justCreated = true;
        chat.draft = draft;
      }
    } else if (!project.assets.length) {
      chat.messages.push({ role: 'assistant', text: '这个项目还没有素材。先在素材库里上传一段视频，并等媒体验证完成，我再开始剪。' });
    } else {
      const draft = makeGenericDraft(usable, nextVersion(chat));
      chat.messages.push({ role: 'assistant', text: '按这个方向剪了一版，先看低清预览，确认后我再导出。' });
      draft.atIndex = chat.messages.length - 1;
      draft.justCreated = true;
      chat.draft = draft;
    }
    renderNav();
    if (currentChat() === chat) {
      const nearBottom = el.threadScroll.scrollHeight - el.threadScroll.scrollTop - el.threadScroll.clientHeight < 40;
      ui.follow = nearBottom;
      if (!nearBottom) ui.hasNew = true;
      renderMessages();
      renderComposer();
      renderHeader();
      if (ui.follow) scrollToBottom();
      else updateJump();
      if (chat.draft) showToast(`新草稿 ${chat.draft.version} 已生成`);
    }
  }

  function stopGeneration() {
    const chat = currentChat();
    if (!chat.generating) return;
    chat.genTimers.forEach(clearTimeout);
    chat.genTimers = [];
    chat.generating = false;
    chat.phase = '';
    chat.messages.push({ role: 'system', text: chat.draft ? `已停止本次生成，草稿保留在 ${chat.draft.version}` : '已停止本次生成' });
    renderMessages();
    renderComposer();
    renderHeader();
    renderNav();
    scrollToBottom();
    el.composerInput.focus();
    showToast('已停止生成');
  }

  /* ---------------- draft actions ---------------- */

  function previewDraft() {
    const chat = currentChat();
    const draft = chat.draft;
    if (!draft || chat.generating) return;
    startPreview(draft.version, draft.title, draft.source);
  }

  function startPreview(version, title, source) {
    const chat = currentChat();
    if (chat.tasks.some(task => task.kind === 'preview' && task.status === 'running')) {
      showToast('已有一条预览正在生成');
      return;
    }
    const task = { id: nextId('preview'), kind: 'preview', version, title, source, progress: 0, status: 'running', timer: null };
    chat.tasks.push(task);
    if (ui.panelCollapsed) setPanel(false, false);
    renderArtifacts();
    el.artifactBody.scrollTop = el.artifactBody.scrollHeight;
    renderNav();
    startTaskTimer(chat, task);
    showToast('正在生成低清预览');
  }

  function previewPlan(version) {
    const chat = currentChat();
    if (!chat.plan) return;
    startPreview(version, chat.plan.title, chat.plan.source);
  }

  function retryPreview() {
    const chat = currentChat();
    if (chat.plan) startPreview(chat.plan.version, chat.plan.title, chat.plan.source);
    else if (chat.draft) startPreview(chat.draft.version, chat.draft.title, chat.draft.source);
  }

  function dismissDraft() {
    const chat = currentChat();
    if (!chat.draft || chat.draft.readOnly) return;
    chat.draft.collapsed = true;
    renderMessages();
    requestAnimationFrame(() => {
      const button = document.querySelector('.draft-collapsed [data-act="expand-draft"]');
      button?.focus();
    });
    showToast('草稿已收起，可稍后回来确认');
  }

  function expandDraft() {
    const chat = currentChat();
    if (!chat.draft) return;
    chat.draft.collapsed = false;
    renderMessages();
    requestAnimationFrame(() => {
      document.querySelector('#draftCard [data-act="confirm-draft"]')?.focus();
      document.querySelector('#draftCard')?.scrollIntoView({ block: 'center' });
    });
  }

  function focusDraft() {
    const chat = currentChat();
    if (chat.draft?.collapsed) chat.draft.collapsed = false;
    renderMessages();
    requestAnimationFrame(() => {
      document.querySelector('#draftCard')?.scrollIntoView({ block: 'center' });
      document.querySelector('#draftCard [data-act="confirm-draft"]')?.focus();
    });
  }

  function confirmDraft() {
    const chat = currentChat();
    const draft = chat.draft;
    if (!draft || draft.readOnly || chat.generating) return;
    chat.plan = makePlan(draft.version, draft);
    draft.readOnly = true;
    draft.collapsed = false;
    chat.flashPlan = true;
    if (ui.panelCollapsed) setPanel(false, false);
    renderAll();
    el.artifactBody.scrollTop = 0;
    requestAnimationFrame(() => {
      document.querySelector('#draftCard [data-act="focus-artifacts"]')?.focus();
    });
    showToast(`已确认方案 ${chat.plan.version} · 可导出成片`);
  }

  function focusArtifacts() {
    if (ui.panelCollapsed) setPanel(false, false);
    el.artifactBody.scrollTop = 0;
    requestAnimationFrame(() => $('#planHeading')?.focus());
  }

  /* ---------------- question ---------------- */

  function selectAnswer(value) {
    const chat = currentChat();
    if (!chat.question || chat.question.submitting) return;
    chat.question.selected = value;
    chat.question.custom = '';
    renderMessages();
    requestAnimationFrame(() => {
      [...document.querySelectorAll('[data-act="answer-option"]')].find(button => button.dataset.value === value)?.focus();
    });
  }

  function submitAnswer() {
    const chat = currentChat();
    const question = chat.question;
    if (!question || question.submitting) return;
    const value = (question.custom.trim() || question.selected || '').trim();
    if (!value) return;
    question.submitting = true;
    renderMessages();
    setTimeout(() => {
      if (chat.question !== question) return;
      chat.messages.push({ role: 'user', text: `片尾署名：${value}` });
      chat.messages.push({ role: 'assistant', text: `记下了，导出时会用「${value}」作为片尾署名。` });
      chat.question = null;
      const nearBottom = el.threadScroll.scrollHeight - el.threadScroll.scrollTop - el.threadScroll.clientHeight < 40;
      ui.follow = nearBottom;
      if (!nearBottom) ui.hasNew = true;
      renderMessages();
      renderComposer();
      if (ui.follow) scrollToBottom();
      else updateJump();
      el.composerInput.focus();
      showToast('回答已提交');
    }, 650);
  }

  /* ---------------- exports & tasks ---------------- */

  function startExport() {
    const chat = currentChat();
    if (!chat.plan) return;
    const profile = $('#exportProfile')?.value || '1080p';
    if (chat.tasks.some(task => task.kind === 'export' && task.status === 'running' && task.version === chat.plan.version)) {
      showToast('这个版本已经在导出');
      return;
    }
    const task = { id: nextId('export'), kind: 'export', version: chat.plan.version, profile, progress: 0, status: 'running', fast: true, timer: null };
    chat.tasks.push(task);
    renderArtifacts();
    el.artifactBody.scrollTop = el.artifactBody.scrollHeight;
    renderNav();
    startTaskTimer(chat, task);
    showToast(`已开始导出 ${chat.plan.version} · ${profile}`);
  }

  function startTaskTimer(chat, task) {
    if (task.timer) clearInterval(task.timer);
    const preview = task.kind === 'preview';
    const step = preview ? 10 : task.fast ? 5 : 3;
    const ms = preview ? 300 : task.fast ? 500 : 1200;
    task.timer = setInterval(() => {
      if (task.status !== 'running') { clearInterval(task.timer); task.timer = null; return; }
      task.progress = Math.min(100, task.progress + step);
      if (currentChat() === chat) updateTaskProgress(task);
      if (task.progress >= 100) {
        clearInterval(task.timer);
        task.timer = null;
        finishTask(chat, task);
      }
    }, ms);
  }

  function updateTaskProgress(task) {
    const node = document.querySelector(`[data-task-id="${task.id}"]`);
    if (!node) return;
    const bar = node.querySelector('.bar i');
    const pct = node.querySelector('.pct');
    if (bar) bar.style.width = `${task.progress}%`;
    if (pct) pct.textContent = `${task.progress}%`;
  }

  function finishTask(chat, task) {
    task.status = 'done';
    task.progress = 100;
    if (task.kind === 'export') {
      chat.history.unshift({ id: nextId('history'), version: task.version, profile: task.profile, when: '刚刚', state: 'done' });
      if (currentChat() === chat) {
        renderArtifacts();
        renderNav();
        showToast(`${task.version} · ${task.profile} 导出完成`);
      } else {
        renderNav();
      }
    } else {
      if (currentChat() === chat) {
        renderArtifacts();
        showToast('低清预览已就绪');
      }
    }
  }

  function stopTask(id) {
    const chat = currentChat();
    const task = chat.tasks.find(item => item.id === id);
    if (!task || task.status !== 'running') return;
    clearInterval(task.timer);
    task.timer = null;
    task.status = 'stopped';
    if (task.kind === 'export') {
      chat.history.unshift({ id: nextId('history'), version: task.version, profile: task.profile, when: '刚刚', state: 'stopped' });
      showToast('已停止导出');
    } else {
      showToast('已停止预览');
    }
    renderArtifacts();
    renderNav();
  }

  /* ---------------- uploads ---------------- */

  function startUpload(file) {
    if (!file) return;
    if (!file.type.startsWith('video/')) { showToast('请选择视频文件'); return; }
    if (ui.newUpload) { showToast('已有一个上传任务在进行'); return; }
    const project = currentProject();
    ui.newUpload = {
      projectId: project.id,
      name: file.name,
      sizeText: formatBytes(file.size),
      progress: 0,
      status: 'uploading',
      paused: false
    };
    renderDrawer();
    renderAssetSummary();
    runUploadTimer();
    showToast(`开始上传 ${file.name}`);
  }

  function runUploadTimer() {
    clearInterval(ui.uploadInterval);
    if (!ui.newUpload) return;
    ui.uploadInterval = setInterval(() => {
      const upload = ui.newUpload;
      if (!upload || upload.status !== 'uploading') return;
      upload.progress = Math.min(100, upload.progress + 2 + Math.random() * 3);
      const pct = Math.round(upload.progress);
      const bar = $('#uploadBar');
      if (bar) bar.style.width = `${pct}%`;
      const sub = document.querySelector('.drawer-asset .asset-sub');
      if (sub && sub.closest('.drawer-asset')?.querySelector('#uploadBar')) sub.textContent = `上传中 ${pct}%`;
      if (upload.progress >= 100) {
        clearInterval(ui.uploadInterval);
        ui.uploadInterval = null;
        upload.status = 'validating';
        renderDrawer();
        renderAssetSummary();
        ui.uploadTimers.push(setTimeout(() => {
          if (!ui.newUpload) return;
          ui.newUpload.status = 'analyzing';
          renderDrawer();
          renderAssetSummary();
          ui.uploadTimers.push(setTimeout(finishUpload, 1500));
        }, 900));
      }
    }, 170);
  }

  function pauseUpload() {
    if (!ui.newUpload) return;
    clearInterval(ui.uploadInterval);
    ui.uploadInterval = null;
    ui.newUpload.status = 'paused';
    renderDrawer();
    renderAssetSummary();
    showToast('已暂停上传');
  }

  function resumeUpload() {
    if (!ui.newUpload) return;
    ui.newUpload.status = 'uploading';
    renderDrawer();
    renderAssetSummary();
    runUploadTimer();
    showToast('继续上传');
  }

  function clearUploadTimers() {
    clearInterval(ui.uploadInterval);
    ui.uploadInterval = null;
    ui.uploadTimers.forEach(clearTimeout);
    ui.uploadTimers = [];
  }

  function cancelUpload() {
    clearUploadTimers();
    const name = ui.newUpload?.name;
    ui.newUpload = null;
    renderDrawer();
    renderAssetSummary();
    showToast(name ? `已取消上传 ${name}` : '已取消上传');
  }

  function finishUpload() {
    const upload = ui.newUpload;
    if (!upload) return;
    const project = projects.find(item => item.id === upload.projectId);
    clearUploadTimers();
    ui.newUpload = null;
    if (project) {
      project.assets.push({
        id: nextId('asset'),
        name: upload.name,
        duration: '—',
        state: 'ready',
        stages: { media: '通过', speech: '就绪', vision: '就绪', index: '就绪' }
      });
    }
    renderAll();
    showToast('素材已就绪');
  }

  function toggleExistingUpload(id) {
    const asset = assetById(id);
    if (!asset?.upload) return;
    asset.upload.paused = !asset.upload.paused;
    renderDrawer();
    renderAssetSummary();
    showToast(asset.upload.paused ? '已暂停上传' : '继续上传');
  }

  function cancelExistingUpload(id) {
    const project = currentProject();
    const asset = project.assets.find(item => item.id === id);
    if (!asset) return;
    project.assets = project.assets.filter(item => item.id !== id);
    if (ui.selectedAsset === id) ui.selectedAsset = null;
    renderAll();
    showToast('已取消上传，残留正在清理');
  }

  /* ---------------- assets ---------------- */

  function selectAsset(id) {
    ui.selectedAsset = id;
    renderDrawer();
    requestAnimationFrame(() => document.querySelector('.drawer-asset.is-selected')?.focus());
  }

  function analyzeAsset(id) {
    const asset = assetById(id);
    if (!asset || asset.state === 'uploading' || asset.stages.vision === '就绪' || asset.stages.vision === '分析中') return;
    asset.stages.vision = '分析中';
    renderDrawer();
    renderAssetSummary();
    showToast('已安排画面分析');
    setTimeout(() => {
      asset.stages.vision = '就绪';
      asset.state = 'ready';
      renderDrawer();
      renderAssetSummary();
      showToast('画面分析完成');
    }, 2200);
  }

  function citeAsset(id) {
    const chat = currentChat();
    if (!chat.references.includes(id)) chat.references.push(id);
    closeDrawer(false);
    renderComposer();
    el.composerInput.focus();
    showToast(`已引用 ${assetById(id)?.name ?? '素材'}`);
  }

  function removeReference(id) {
    const chat = currentChat();
    chat.references = chat.references.filter(value => value !== id);
    renderComposer();
    renderAssetSummary();
  }

  /* ---------------- player ---------------- */

  function openPlayer(payload) {
    const trigger = document.activeElement;
    ui.player = {
      ...payload,
      elapsed: 0,
      playing: false,
      timer: null,
      trigger
    };
    el.playerTitle.textContent = payload.title;
    el.playerMeta.textContent = payload.meta;
    el.playerSource.textContent = payload.source;
    el.playerKind.textContent = payload.kind;
    el.playerBadge.textContent = payload.badge;
    el.playerNote.textContent = payload.note;
    el.playerTime.textContent = '00:00';
    el.playerDuration.textContent = formatTime(payload.duration);
    el.playerProgress.querySelector('i').style.width = '0%';
    el.playerProgress.setAttribute('aria-valuenow', '0');
    el.playerImage.hidden = payload.image === false;
    el.playerPlaceholder.hidden = payload.image !== false;
    el.playerDialog.classList.remove('is-playing');
    el.playerDownload.hidden = payload.image === false;
    setPlayIcon(false);
    el.playerVeil.hidden = false;
    el.playerVeil.inert = false;
    document.body.classList.add('player-open');
    requestAnimationFrame(() => {
      el.playerVeil.classList.add('is-open');
      el.playerClose.focus();
    });
  }

  function closePlayer() {
    if (!ui.player) return;
    stopPlayback();
    const trigger = ui.player.trigger;
    ui.player = null;
    document.body.classList.remove('player-open');
    el.playerVeil.classList.remove('is-open');
    setTimeout(() => {
      el.playerVeil.hidden = true;
      el.playerVeil.inert = true;
    }, 190);
    if (trigger && document.contains(trigger)) trigger.focus();
    else el.composerInput.focus();
  }

  function setPlayIcon(playing) {
    const name = playing ? 'pause' : 'play';
    el.playerPlayBtn.querySelector('use')?.setAttribute('href', `#i-${name}`);
    el.playerPlayBtn.setAttribute('aria-label', playing ? '暂停' : '播放');
    el.playerBigPlay.querySelector('use')?.setAttribute('href', '#i-play');
    el.playerBigPlay.setAttribute('aria-label', playing ? '暂停' : '播放');
  }

  function togglePlayback() {
    if (!ui.player) return;
    if (ui.player.playing) {
      stopPlayback();
      return;
    }
    if (ui.player.elapsed >= ui.player.duration) ui.player.elapsed = 0;
    ui.player.playing = true;
    el.playerDialog.classList.add('is-playing');
    setPlayIcon(true);
    ui.player.timer = setInterval(() => {
      if (!ui.player) return;
      ui.player.elapsed += 0.25;
      if (ui.player.elapsed >= ui.player.duration) {
        ui.player.elapsed = ui.player.duration;
        updatePlayerProgress();
        stopPlayback();
        return;
      }
      updatePlayerProgress();
    }, 250);
  }

  function stopPlayback() {
    if (!ui.player) return;
    clearInterval(ui.player.timer);
    ui.player.timer = null;
    ui.player.playing = false;
    el.playerDialog.classList.remove('is-playing');
    setPlayIcon(false);
  }

  function updatePlayerProgress() {
    if (!ui.player) return;
    const ratio = ui.player.duration ? ui.player.elapsed / ui.player.duration : 0;
    el.playerProgress.querySelector('i').style.width = `${Math.min(100, ratio * 100)}%`;
    el.playerProgress.setAttribute('aria-valuenow', String(Math.round(ratio * 100)));
    el.playerTime.textContent = formatTime(ui.player.elapsed);
  }

  function seekPlayer(event) {
    if (!ui.player) return;
    const rect = el.playerProgress.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    ui.player.elapsed = ratio * ui.player.duration;
    updatePlayerProgress();
  }

  function playPlan(version, profile) {
    const plan = currentChat().plan;
    if (!plan || !profile) return;
    openPlayer({
      title: plan.title,
      meta: `${version} · ${plan.format} · ${plan.clipCount} · ${plan.subtitleCount}`,
      source: plan.source,
      badge: `${profile} 成片`,
      kind: '正式成片',
      duration: parseTime(plan.duration) || 45,
      image: true,
      note: '成片来自已确认版本；切换会话不会改变正在导出的来源。'
    });
  }

  function playPreview(id) {
    const chat = currentChat();
    const task = chat.tasks.find(item => item.id === id);
    if (!task) return;
    openPlayer({
      title: task.title,
      meta: `${task.version} · 1080 × 1920 · 4 段`,
      source: task.source,
      badge: '低清预览',
      kind: '低清预览',
      duration: 45,
      image: true,
      note: '预览用于查看节奏和字幕；正式导出仍需确认版本后单独发起。'
    });
  }

  function playAsset(id) {
    const asset = assetById(id);
    if (!asset || !canPlayAsset(asset)) return;
    openPlayer({
      title: asset.name,
      meta: `${asset.duration} · ${assetStateText(asset)}`,
      source: currentProject().name,
      badge: '原片',
      kind: '原片回看',
      duration: parseTime(asset.duration) || 45,
      image: false,
      note: '原片回看用于核查证据；不会触发导出。'
    });
  }

  function playHistory(version, profile) {
    playPlan(version, profile);
  }

  function download(profile, version) {
    showToast(`正在准备 ${version} · ${profile} 下载`);
    setTimeout(() => showToast(`${version} · ${profile} 下载已开始`), 700);
  }

  /* ---------------- toast & focus ---------------- */

  function showToast(text) {
    el.toast.textContent = text;
    el.toast.classList.add('is-visible');
    clearTimeout(ui.toastTimer);
    ui.toastTimer = setTimeout(() => el.toast.classList.remove('is-visible'), 2800);
  }

  function trapFocus(container, event) {
    const focusable = [...container.querySelectorAll('button,[href],input,textarea,[tabindex]:not([tabindex="-1"])')]
      .filter(node => !node.disabled && node.offsetParent !== null);
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  /* ---------------- events ---------------- */

  document.addEventListener('click', event => {
    const trigger = event.target.closest('[data-act]');
    if (!trigger) return;
    const action = trigger.dataset.act;
    const id = trigger.dataset.id;
    switch (action) {
      case 'select-project': setProject(id); break;
      case 'select-chat': setChat(id); break;
      case 'new-chat': newChat(); break;
      case 'toggle-left': setLeft(!ui.leftCollapsed); break;
      case 'toggle-panel': setPanel(!ui.panelCollapsed); break;
      case 'open-drawer': openDrawer(trigger.dataset.focus); break;
      case 'close-drawer': closeDrawer(); break;
      case 'open-asset': openAsset(id); break;
      case 'select-asset': selectAsset(id); break;
      case 'play-asset': playAsset(id); break;
      case 'cite-asset': citeAsset(id); break;
      case 'analyze-asset': analyzeAsset(id); break;
      case 'remove-ref': removeReference(id); break;
      case 'filter-assets': ui.filter = trigger.dataset.filter; renderDrawer(); break;
      case 'pause-upload': pauseUpload(); break;
      case 'resume-upload': resumeUpload(); break;
      case 'cancel-upload': cancelUpload(); break;
      case 'toggle-existing-upload': toggleExistingUpload(id); break;
      case 'cancel-existing-upload': cancelExistingUpload(id); break;
      case 'dismiss-draft': dismissDraft(); break;
      case 'expand-draft': expandDraft(); break;
      case 'preview-draft': previewDraft(); break;
      case 'confirm-draft': confirmDraft(); break;
      case 'focus-artifacts': focusArtifacts(); break;
      case 'focus-draft': focusDraft(); break;
      case 'answer-option': selectAnswer(trigger.dataset.value); break;
      case 'submit-answer': submitAnswer(); break;
      case 'preview-plan': previewPlan(trigger.dataset.version); break;
      case 'play-plan': playPlan(trigger.dataset.version, trigger.dataset.profile); break;
      case 'play-preview': playPreview(id); break;
      case 'retry-preview': retryPreview(); break;
      case 'play-history': playHistory(trigger.dataset.version, trigger.dataset.profile); break;
      case 'download': download(trigger.dataset.profile, trigger.dataset.version); break;
      case 'download-player':
        showToast('正在准备下载…');
        setTimeout(() => showToast('下载已开始'), 700);
        break;
      case 'start-export': startExport(); break;
      case 'stop-task': stopTask(id); break;
      case 'toggle-play': togglePlayback(); break;
      case 'close-player': closePlayer(); break;
      case 'stop-gen': stopGeneration(); break;
      case 'jump-latest': scrollToBottom(); break;
      default: break;
    }
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      if (ui.player) { event.preventDefault(); closePlayer(); return; }
      if (ui.drawerOpen) { event.preventDefault(); closeDrawer(); return; }
    }
    if (event.key === 'Tab') {
      if (ui.player) { trapFocus(el.playerDialog, event); return; }
      if (ui.drawerOpen) trapFocus(el.drawer, event);
    }
    if ((event.key === 'Enter' || event.key === ' ') && event.target.matches?.('.drawer-asset[data-act="select-asset"]')) {
      event.preventDefault();
      selectAsset(event.target.dataset.id);
    }
  });

  el.composerForm.addEventListener('submit', event => {
    event.preventDefault();
    sendMessage();
  });

  el.composerInput.addEventListener('input', () => {
    const chat = currentChat();
    chat.input = el.composerInput.value;
    el.sendBtn.disabled = !chat.input.trim() || chat.generating;
    autoGrow();
  });

  el.composerInput.addEventListener('keydown', event => {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey) && !event.isComposing) {
      event.preventDefault();
      sendMessage();
    }
  });

  el.threadScroll.addEventListener('scroll', () => {
    const nearBottom = el.threadScroll.scrollHeight - el.threadScroll.scrollTop - el.threadScroll.clientHeight < 40;
    ui.follow = nearBottom;
    if (nearBottom) ui.hasNew = false;
    updateJump();
  });

  document.addEventListener('input', event => {
    if (event.target.id === 'questionCustom') {
      const chat = currentChat();
      if (!chat.question) return;
      chat.question.custom = event.target.value;
      chat.question.selected = '';
      document.querySelectorAll('[data-act="answer-option"]').forEach(button => button.classList.remove('on'));
      const submit = document.querySelector('[data-act="submit-answer"]');
      if (submit) submit.disabled = !chat.question.custom.trim();
    }
  });

  el.dropzone.addEventListener('click', () => el.fileInput.click());
  el.fileInput.addEventListener('change', () => {
    const file = el.fileInput.files?.[0];
    el.fileInput.value = '';
    if (file) startUpload(file);
  });

  ['dragenter', 'dragover'].forEach(type => el.dropzone.addEventListener(type, event => {
    event.preventDefault();
    el.dropzone.classList.add('is-over');
  }));
  ['dragleave', 'drop'].forEach(type => el.dropzone.addEventListener(type, event => {
    event.preventDefault();
    el.dropzone.classList.remove('is-over');
  }));
  el.dropzone.addEventListener('drop', event => {
    const file = event.dataTransfer?.files?.[0];
    if (file) startUpload(file);
  });

  el.assetSearch.addEventListener('input', () => {
    ui.query = el.assetSearch.value;
    renderDrawer();
  });

  el.playerVeil.addEventListener('click', event => {
    if (event.target === el.playerVeil) closePlayer();
  });

  el.playerProgress.addEventListener('click', seekPlayer);
  el.playerProgress.addEventListener('keydown', event => {
    if (!ui.player) return;
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight' && event.key !== 'Home' && event.key !== 'End') return;
    event.preventDefault();
    if (event.key === 'Home') ui.player.elapsed = 0;
    else if (event.key === 'End') ui.player.elapsed = ui.player.duration;
    else ui.player.elapsed = Math.min(ui.player.duration, Math.max(0, ui.player.elapsed + (event.key === 'ArrowRight' ? 2 : -2)));
    updatePlayerProgress();
  });

  el.playerImage.addEventListener('error', () => {
    el.playerImage.hidden = true;
    el.playerPlaceholder.hidden = false;
  });

  /* ---------------- boot ---------------- */

  el.drawer.inert = true;
  el.playerVeil.inert = true;
  renderAll();
  requestAnimationFrame(() => {
    scrollToBottom();
    projects.forEach(project => project.chats.forEach(chat => {
      chat.tasks.filter(task => task.status === 'running').forEach(task => startTaskTimer(chat, task));
    }));
  });
})();
