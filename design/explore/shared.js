window.STORY = {
  brand: { name: '叙帧', badge: 'STUDIO' },
  user: { name: '林屿', team: '内容组', initial: '林' },
  project: { name: '叙帧 2.0 · 秋季发布', chats: 5, assets: 6 },
  projects: [
    { name: '叙帧 2.0 · 秋季发布', count: 5, active: true },
    { name: '品牌年报 · 影像', count: 2 },
    { name: '用户故事 · Q4', count: 3 }
  ],
  chats: [
    { id: 'c1', title: '45 秒竖屏预告', active: true, badge: '待确认' },
    { id: 'c2', title: '开场 Keynote 金句' },
    { id: 'c3', title: '用户证言混剪', badge: '运行中' },
    { id: 'c4', title: '产品演示 30 秒' },
    { id: 'c5', title: '发布会花絮' }
  ],
  assets: [
    { id: 'a1', name: '创始人访谈_林屿.mp4', duration: '12:38', state: 'ready', caps: ['语音', '画面'], referenced: true },
    { id: 'a2', name: '主舞台_Keynote_4K.mp4', duration: '38:12', state: 'analyzing', caps: ['语音', '画面分析中'] },
    { id: 'a3', name: '用户证言_周女士.mp4', duration: '02:06', state: 'ready', caps: ['语音', '画面'] },
    { id: 'a4', name: '产品演示_录屏.mp4', duration: '05:41', state: 'idle', caps: ['待分析'] },
    { id: 'a5', name: '后台花絮_A.mp4', duration: '01:06', state: 'uploading', progress: 62, uploaded: '662 MB', total: '1.06 GB' }
  ],
  messages: {
    older: '12 条更早消息',
    user: '压到 45 秒，结尾那句保留。',
    assistant: '已压到 45 秒，4 段都来自林屿的访谈，结尾落在「把表达还给你」。',
    note: '保留原声与中文字幕；4 段硬切，结尾留 0.5 秒黑场。'
  },
  draft: {
    version: 'V2',
    status: '待确认',
    title: '预告 · 竖屏 45 秒',
    duration: '00:45',
    format: '1080 × 1920',
    clipCount: '4 段',
    subtitleCount: '8 条字幕',
    source: '创始人访谈_林屿.mp4',
    segments: [
      { no: '01', out: '00:00–00:08', src: '03:12–03:20', text: '过去怎么剪', grow: 8 },
      { no: '02', out: '00:08–00:19', src: '07:41–07:52', text: '效率与时间', grow: 11 },
      { no: '03', out: '00:19–00:31', src: '09:05–09:17', text: '从素材到成片', grow: 12 },
      { no: '04', out: '00:31–00:45', src: '11:26–11:40', text: '把表达还给你', grow: 14 }
    ]
  },
  question: {
    prompt: '片尾署名用哪一个？',
    hint: '导出时写入片尾 2 秒。',
    options: ['叙帧团队', '林屿', '不署名'],
    selected: '叙帧团队'
  },
  plan: {
    version: 'V1',
    status: '已确认',
    title: '预告 · 竖屏 45 秒',
    duration: '00:45',
    format: '1080 × 1920',
    clips: '4 段',
    subtitles: '8 条字幕',
    source: '创始人访谈_林屿.mp4',
    spec: 'H.264 · AAC · 1080p',
    task: {
      label: '正式导出 · 1080p',
      target: 'V1 · 输出 1',
      progress: 62
    },
    history: { profile: '720p', when: '昨天 16:12', state: '已完成' }
  },
  storage: '2.4 / 5 GiB',
  model: 'deepseek-flash',
  poster: 'assets/C1-192558122.jpg'
};
