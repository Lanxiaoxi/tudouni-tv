/* ============================================================
   discovery.js · 首页发现流 / 分类页 / 历史页 / 视图切换 / 主题
   （重构后新增：内容行数据来自聚合源 ac=videolist 最新列表，
    前端按 type_name 关键词分类，不依赖采集站类型参数）
   ============================================================ */

/* ---------- 工具 ---------- */
function esc(s) {
    return String(s == null ? '' : s)
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

// 按 type_name 归类：动漫/综艺/剧集/电影
// series 用"剧(?![情片])"排除"剧情片/喜剧片"等电影类型误入剧集
function classifyType(typeName) {
    const t = typeName || '';
    if (/(动漫|动画|番剧)/.test(t)) return 'anime';
    if (/(综艺|真人秀|选秀|音乐节目)/.test(t)) return 'variety';
    if (/(剧(?![情片]))/.test(t)) return 'series';
    return 'movie';
}

const CAT_INFO = {
    movie:   { title: '电影', sub: '精选院线佳作 · 大片云集' },
    series:  { title: '剧集', sub: '热播好剧 · 全集同步更新' },
    anime:   { title: '动漫', sub: '国漫精品 · 高分番剧' },
    variety: { title: '综艺', sub: '王牌综艺 · 轻松下饭' }
};

/* ---------- 数据获取 ---------- */
// 从单个源拉取最新列表（重构后：调后端 /api/vodlist）
async function fetchVodList(apiId, page) {
    try {
        const site = API_SITES[apiId];
        if (!site) return [];
        const data = await window.Api.get('/api/vodlist', { source: apiId, pg: page || 1 });
        return (data && data.items) || [];
    } catch (e) {
        console.warn('分类列表获取失败 [' + apiId + ']:', e.message);
        return [];
    }
}

// 聚合多个普通源的最新列表，可选按大类过滤（字符串或字符串数组）
async function aggregateVodList(page, filterCat) {
    const keys = Object.keys(API_SITES).filter(k => !API_SITES[k].adult).slice(0, 4);
    const results = await Promise.all(keys.map(k => fetchVodList(k, page)));
    let items = [];
    results.forEach(r => { if (Array.isArray(r)) items = items.concat(r); });
    // 去重（同名保留第一个）
    const seen = new Set();
    items = items.filter(it => {
        const k = it.vod_name || '';
        if (seen.has(k)) return false;
        seen.add(k);
        return true;
    });
    if (filterCat) {
        if (Array.isArray(filterCat)) {
            items = items.filter(it => filterCat.includes(classifyType(it.type_name)));
        } else {
            items = items.filter(it => classifyType(it.type_name) === filterCat);
        }
    }
    return items;
}

/* ---------- 海报卡片渲染 ---------- */
const DISCOVERY_PALETTE = [
    ['#1a2a6c', '#2c3e50'], ['#134e5e', '#4e4376'], ['#232526', '#414345'], ['#0f2027', '#2c5364'],
    ['#42275a', '#734b6d'], ['#2b1055', '#7597de'], ['#355c7d', '#6c5b7b'], ['#3e5151', '#decba4'],
    ['#141e30', '#243b55'], ['#0f0c29', '#302b63'], ['#000428', '#004e92'], ['#1a2a6c', '#b21f1f']
];

function posterArt(item, i) {
    const palette = DISCOVERY_PALETTE[i % DISCOVERY_PALETTE.length];
    const ch = (item.vod_name || '影').charAt(0);
    const gid = 'art' + (item.vod_id || ('x' + i)) + '_' + i;
    return `<svg class="art" viewBox="0 0 80 120" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    <defs><linearGradient id="${gid}" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${palette[0]}"/><stop offset="1" stop-color="${palette[1]}"/>
    </linearGradient></defs>
    <rect width="80" height="120" fill="url(#${gid})"/>
    <circle cx="80" cy="0" r="36" fill="#ffb020" opacity=".08"/>
    <circle cx="0" cy="120" r="42" fill="#ffd35c" opacity=".06"/>
    <text x="40" y="68" font-family="'Noto Sans SC','Microsoft YaHei',sans-serif" font-size="36" fill="rgba(255,255,255,.6)" text-anchor="middle" font-weight="700">${esc(ch)}</text>
  </svg>`;
}

function posterCardHTML(item, i, extraClass) {
    const safeId = (item.vod_id || '').toString().replace(/[^\w-]/g, '');
    const safeName = esc(item.vod_name || '未知视频');
    const sourceCode = item.source_code || '';
    const score = item.vod_score ? esc(item.vod_score) : '';
    const year = item.vod_year ? esc(item.vod_year) : '';
    const typeName = item.type_name ? esc(item.type_name) : '';
    const remarks = item.vod_remarks ? esc(item.vod_remarks) : '';
    const cover = (item.vod_pic && item.vod_pic.startsWith('http')) ? item.vod_pic : '';
    const sourceName = item.source_name ? esc(item.source_name) : '';

    return `
    <div class="poster-card ${extraClass || ''}" onclick="showDetails('${safeId}','${safeName}','${sourceCode}')" role="button" tabindex="0"
         onkeydown="if(event.key==='Enter'){showDetails('${safeId}','${safeName}','${sourceCode}')}">
        <div class="poster">
            ${posterArt(item, i)}
            ${cover ? `<img class="cover" src="${esc(cover)}" alt="${safeName}" loading="lazy" onerror="this.style.display='none'">` : ''}
            ${sourceName ? `<span class="src-tag">${sourceName}</span>` : ''}
            ${score ? `<span class="score"><svg viewBox="0 0 24 24"><path d="m12 2 2.9 6.3 6.9.8-5.1 4.7 1.4 6.8L12 17.2 5.9 20.6l1.4-6.8L2.2 9.1l6.9-.8z"/></svg>${score}</span>` : ''}
            ${remarks ? `<span class="ep">${remarks}</span>` : ''}
            <div class="play-hint"><div class="ring"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.14v13.72a1 1 0 0 0 1.52.85l11-6.86a1 1 0 0 0 0-1.7l-11-6.86A1 1 0 0 0 8 5.14z"/></svg></div></div>
        </div>
        <div class="p-name" title="${safeName}">${safeName}</div>
        <div class="p-sub"><span>${year || '未知年份'}</span>${typeName ? `<span>·</span><span>${typeName}</span>` : ''}</div>
    </div>`;
}

// 从数据池筛选并渲染一行（数据由后端 /api/home 统一拉取去重，前端保留分类业务）
function renderRow(stripId, filterCat, count) {
    const strip = document.getElementById(stripId);
    if (!strip) return;
    const pool = window.homeDataPool || [];
    let items = pool;
    // 按大类过滤
    if (filterCat) {
        if (Array.isArray(filterCat)) {
            items = items.filter(it => filterCat.includes(classifyType(it.type_name)));
        } else {
            items = items.filter(it => classifyType(it.type_name) === filterCat);
        }
    }
    const slice = items.slice(0, count || 12);
    if (!slice.length) {
        strip.innerHTML = `<div class="row-empty">暂时没有内容，稍后再来看看</div>`;
        return;
    }
    try {
        strip.innerHTML = slice.map((it, i) => posterCardHTML(it, i)).join('');
    } catch (e) {
        strip.innerHTML = `<div class="row-empty">加载失败，请刷新重试</div>`;
    }
}

/* ---------- 首页 Hero ---------- */
// Hero 背景艺术图（渐变 + 首字），无封面时的兜底
function heroArtSvg(item, i) {
    const palette = DISCOVERY_PALETTE[i % DISCOVERY_PALETTE.length];
    const ch = (item.vod_name || '影').charAt(0);
    const gid = 'hart' + (item.vod_id || ('h' + i));
    return `<svg class="hero-art-svg" viewBox="0 0 1200 600" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
    <defs><linearGradient id="${gid}" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${palette[0]}"/><stop offset="1" stop-color="${palette[1]}"/>
    </linearGradient></defs>
    <rect width="1200" height="600" fill="url(#${gid})"/>
    <circle cx="1050" cy="120" r="180" fill="#ffffff" opacity=".05"/>
    <circle cx="200" cy="520" r="220" fill="#000000" opacity=".12"/>
    <text x="600" y="350" font-family="'Noto Sans SC','Microsoft YaHei',sans-serif" font-size="200" fill="rgba(255,255,255,.14)" text-anchor="middle" font-weight="900">${esc(ch)}</text>
  </svg>`;
}

// 渲染 Hero 背景海报（SVG 渐变始终渲染 + 真实封面叠加，封面失败自动回退到渐变）
function renderHeroArt(heroBanner, pick, idx) {
    if (!heroBanner) return;
    heroBanner.querySelectorAll('.hero-art, .hero-art-svg').forEach(el => el.remove());
    // SVG 渐变兜底（始终存在，img 盖在其上）
    heroBanner.insertAdjacentHTML('afterbegin', heroArtSvg(pick, idx));
    // 有封面则叠加真实海报；加载失败移除后露出渐变
    const cover = (pick.vod_pic && pick.vod_pic.startsWith('http')) ? pick.vod_pic : '';
    if (cover) {
        const img = document.createElement('img');
        img.className = 'hero-art';
        img.src = cover;
        img.alt = '';
        // 关键层级用 inline style 注入（不依赖 CSS 文件，避免缓存导致渲染失效）
        // img 必须在 svg 之上（svg 后插入 DOM，同层级会盖住 img）；
        // 宽度交给 CSS（大屏 55% 右侧构图 / 小屏媒体查询 100%）
        img.style.cssText = 'position:absolute;top:0;right:0;height:100%;z-index:1;object-fit:cover;object-position:right center';
        img.onerror = function () { this.remove(); };
        heroBanner.insertBefore(img, heroBanner.firstChild);
    }
}

// hero 渲染（数据从 homeDataPool 选片：海报 > 评分，分类/选片业务保留前端）
function renderHero() {
    const heroBody = document.getElementById('heroBody');
    if (!heroBody) return;
    const heroBanner = document.getElementById('heroBanner');
    const pool = window.homeDataPool || [];
    // 防御性去重（后端已去重，此处兜底）
    const seen = new Set();
    const items = [];
    pool.forEach(it => {
        const k = it.vod_name || '';
        if (!seen.has(k)) { seen.add(k); items.push(it); }
    });
    // 选片优先级：海报 > 评分。全量按评分降序后取第一个有海报的候选（=有海报中评分最高）；
    // 全部无海报时才退化为纯评分排序；无评分则兜底取第一条
    const byScore = [...items].sort((a, b) => parseFloat(b.vod_score || 0) - parseFloat(a.vod_score || 0));
    const hasCover = i => i.vod_pic && String(i.vod_pic).startsWith('http');
    const pick = byScore.find(hasCover) || byScore.find(i => !hasCover(i)) || items[0];
    if (!pick) {
        // 未登录时不显示兜底文案（登录成功后页面会刷新重载数据），避免日志风暴
        if (typeof isPasswordVerified === 'function' && !isPasswordVerified()) return;
        heroBody.innerHTML = `<h1 class="hero-title">TudouniTV</h1><p class="hero-desc">自由观影，畅享精彩</p>`;
        return;
    }
    try {
        // 背景海报（渐变兜底 + 真实封面）
        renderHeroArt(heroBanner, pick, 0);

        const safeId = (pick.vod_id || '').toString().replace(/[^\w-]/g, '');
        const safeName = esc(pick.vod_name || '热门影视');
        const score = pick.vod_score ? esc(pick.vod_score) : '';
        const typeName = pick.type_name ? esc(pick.type_name) : '';
        const year = pick.vod_year ? esc(pick.vod_year) : '';
        const remarks = pick.vod_remarks ? esc(pick.vod_remarks) : '';
        let desc = (pick.vod_content || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
        if (desc.length > 90) desc = desc.slice(0, 90) + '…';

        heroBody.innerHTML = `
            <span class="hero-badge"><i></i>${remarks ? esc(remarks) : '今日精选'}</span>
            <h1 class="hero-title">${safeName}</h1>
            <div class="hero-meta">
                ${score ? `<span class="score-pill"><svg viewBox="0 0 24 24"><path d="m12 2 2.9 6.3 6.9.8-5.1 4.7 1.4 6.8L12 17.2 5.9 20.6l1.4-6.8L2.2 9.1l6.9-.8z"/></svg>${score}</span>` : ''}
                ${typeName ? `<span class="tag-pill">${typeName}</span>` : ''}
                ${year ? `<span class="tag-pill">${year}</span>` : ''}
                <span class="tag-pill">${esc(pick.source_name || '多源聚合')}</span>
            </div>
            ${desc ? `<p class="hero-desc">${desc}</p>` : ''}
            <div class="hero-actions">
                <button class="btn-hero btn-play" onclick="showDetails('${safeId}','${safeName}','${esc(pick.source_code || '')}')">
                    <svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.14v13.72a1 1 0 0 0 1.52.85l11-6.86a1 1 0 0 0 0-1.7l-11-6.86A1 1 0 0 0 8 5.14z"/></svg>
                    立即播放
                </button>
                <button class="btn-hero btn-detail" onclick="showDetails('${safeId}','${safeName}','${esc(pick.source_code || '')}')">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/></svg>
                    查看详情
                </button>
            </div>`;
    } catch (e) {
        console.error('Hero 渲染失败:', e);
        heroBody.innerHTML = `<h1 class="hero-title">TudouniTV</h1><p class="hero-desc">自由观影，畅享精彩</p>`;
    }
}

async function renderHomeRows() {
    // 三行先显示 loading
    ['stripSeries', 'stripMovies', 'stripAnime'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.innerHTML = `<div class="row-loading"><div class="spin"></div><span>加载中...</span></div>`;
    });
    try {
        // 后端 /api/home 一次完成"拉取 4 源 × 2 页 + 去重"（无状态通用）；
        // 前端从 pool 做分类分组与 hero 选片（业务逻辑保留前端，增删种类不动后端）
        const data = await window.Api.get('/api/home');
        const items = (data && data.items) || [];
        if (!items.length) throw new Error('首页数据为空');
        window.homeDataPool = items;
        renderRow('stripSeries', 'series', 12);
        renderRow('stripMovies', 'movie', 12);
        renderRow('stripAnime', ['anime', 'variety'], 14); // 动漫·综艺行：合并动漫与综艺
    } catch (e) {
        console.error('首页内容加载失败:', e);
        ['stripSeries', 'stripMovies', 'stripAnime'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.innerHTML = `<div class="row-empty">加载失败，请刷新重试</div>`;
        });
    }
    renderHero();
}

/* ---------- 分类页 ---------- */
let curCat = 'movie';
let curType = '全部';
let curSort = '综合';
let catPage = 1;
let catPool = [];
let catFilterTypes = ['全部'];
let catLoading = false;

function goCategory(cat) {
    if (!CAT_INFO[cat]) cat = 'movie';
    curCat = cat;
    switchView('category');
    renderCategory(cat);
}

function renderCategory(cat) {
    const info = CAT_INFO[cat];
    document.getElementById('catTitle').textContent = info.title;
    document.getElementById('catSub').textContent = info.sub;
    catPage = 1;
    catPool = [];
    catFilterTypes = ['全部'];
    renderCatFilter();
    document.getElementById('catGrid').innerHTML =
        `<div class="row-loading" style="grid-column:1/-1"><div class="spin"></div><span>加载中...</span></div>`;
    document.getElementById('catCount').textContent = '';
    loadCategoryPage();
}

function renderCatFilter() {
    const box = document.getElementById('catFilter');
    if (!box) return;
    box.innerHTML = catFilterTypes.map(t =>
        `<button class="chip ${t === curType ? 'active' : ''}" data-type="${t}">${t}</button>`
    ).join('');
    box.querySelectorAll('.chip').forEach(chip => {
        chip.addEventListener('click', () => {
            box.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            curType = chip.dataset.type;
            catPage = 1;
            catPool = [];
            loadCategoryPage();
        });
    });
}

function applyCatSort() {
    const box = document.getElementById('catSort');
    if (!box) return;
    const sorts = ['综合', '最新', '高分'];
    box.querySelectorAll('.chip').forEach((chip, i) => {
        chip.classList.toggle('active', sorts[i] === curSort);
        chip.onclick = () => {
            box.querySelectorAll('.chip').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            curSort = sorts[i];
            loadCategoryPage();
        };
    });
}

async function loadCategoryPage() {
    if (catLoading) return;
    catLoading = true;
    const grid = document.getElementById('catGrid');
    const loadMoreBtn = document.getElementById('btnLoadMore');
    try {
        // 并行拉取 4 页最新列表，再按大类过滤
        const pages = await Promise.all([1, 2, 3, 4].map(pg => aggregateVodList(pg, null)));
        let items = [];
        pages.forEach(r => { items = items.concat(r); });
        const seen = new Set();
        items = items.filter(it => {
            const k = (it.vod_name || '') + (it.vod_id || '');
            if (seen.has(k)) return false;
            seen.add(k);
            return true;
        });
        // 大类过滤 + 类型标签
        const filtered = items.filter(it => classifyType(it.type_name) === curCat);
        if (catFilterTypes.length === 1) {
            catFilterTypes = ['全部', ...new Set(filtered.map(i => i.type_name))].slice(0, 14);
            renderCatFilter();
        }
        const byType = curType === '全部' ? filtered : filtered.filter(i => (i.type_name || '') === curType);
        catPool = catPool.concat(byType);
        catPage++;

        // 排序
        let list = [...catPool];
        if (curSort === '高分') list.sort((a, b) => parseFloat(b.vod_score || 0) - parseFloat(a.vod_score || 0));
        else if (curSort === '最新') list.sort((a, b) => String(b.vod_year || '').localeCompare(String(a.vod_year || '')));

        // 渲染当前可见部分（每次加载 +24）
        const visible = list.slice(0, catPage * 24);
        grid.innerHTML = visible.length
            ? visible.map((it, i) => posterCardHTML(it, i)).join('')
            : `<div class="row-empty" style="grid-column:1/-1">该分类暂时没有内容</div>`;
        document.getElementById('catCount').textContent = `共 ${list.length} 部内容`;

        const hasMore = visible.length < list.length;
        loadMoreBtn.style.display = hasMore ? '' : 'none';
        loadMoreBtn.disabled = false;
        loadMoreBtn.textContent = '加载更多';
    } catch (e) {
        console.error('分类加载失败:', e);
        grid.innerHTML = `<div class="row-empty" style="grid-column:1/-1">加载失败，请刷新重试</div>`;
    } finally {
        catLoading = false;
        applyCatSort();
    }
}

/* ---------- 历史记录页 ---------- */
let histPageFilter = 'all';

function histDayGroup(ts) {
    if (!ts) return '更早';
    const date = new Date(ts);
    const now = new Date();
    const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const startYesterday = startToday - 86400000;
    if (date.getTime() >= startToday) return '今天';
    if (date.getTime() >= startYesterday) return '昨天';
    return '更早';
}

function renderHistoryPage() {
    const groups = ['今天', '昨天', '更早'];
    const history = (typeof getViewingHistory === 'function') ? getViewingHistory() : [];
    const total = history.length;
    const countEl = document.getElementById('histCount');
    if (countEl) countEl.textContent = `共 ${total} 部内容`;

    const filtered = history.filter(h => {
        if (histPageFilter === 'all') return true;
        const isSeries = h.episodes && Array.isArray(h.episodes) && h.episodes.length > 1;
        return histPageFilter === 'series' ? isSeries : !isSeries;
    });

    const groupsEl = document.getElementById('histGroups');
    if (!filtered.length) {
        groupsEl.innerHTML = `
            <div class="hist-empty">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
                <h3>暂无观看历史</h3>
                <p>看过的影视会出现在这里，方便你随时继续观看</p>
                <button class="hist-cta primary" onclick="goHome()">去逛逛首页</button>
            </div>`;
        return;
    }

    groupsEl.innerHTML = groups.map(g => {
        const list = filtered.filter(h => histDayGroup(h.timestamp) === g);
        if (!list.length) return '';
        return `
        <div class="history-group">
            <div class="history-group-title"><span class="mark"></span>${g}<span>${list.length} 部</span></div>
            ${list.map(histCardHTML).join('')}
        </div>`;
    }).join('');
}

function histCardHTML(h) {
    const safeTitle = esc(h.title || '未知视频');
    const safeSource = esc(h.sourceName || '未知来源');
    const isSeries = h.episodes && Array.isArray(h.episodes) && h.episodes.length > 1;
    const episodeText = h.episodeIndex !== undefined ? `第${h.episodeIndex + 1}集` : (isSeries ? '' : '正片');
    const epLabel = episodeText || '正片';
    const safeURL = encodeURIComponent(h.url || '');

    let pct = 0;
    if (h.playbackPosition && h.duration && h.playbackPosition > 10 && h.playbackPosition < h.duration * 0.95) {
        pct = Math.round((h.playbackPosition / h.duration) * 100);
    }
    const timeText = h.timestamp ? (typeof formatTimestamp === 'function' ? formatTimestamp(h.timestamp) : '') : '';

    return `
    <div class="history-item" onclick="playFromHistory('${esc(h.url || '')}', '${safeTitle}', ${h.episodeIndex || 0}, ${h.playbackPosition || 0})">
        <button class="hist-del" onclick="event.stopPropagation(); deleteHistoryItem('${safeURL}')" title="删除记录" aria-label="删除记录">
            <svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path></svg>
        </button>
        <div class="hist-poster">
            <svg viewBox="0 0 80 120" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
                <rect width="80" height="120" fill="#1d2430"/>
                <circle cx="40" cy="48" r="18" fill="none" stroke="#ffb020" stroke-opacity=".5" stroke-width="2.5"/>
                <path d="M36 42l12 6-12 6z" fill="#ffb020" fill-opacity=".8"/>
                <text x="40" y="98" font-family="'Noto Sans SC',sans-serif" font-size="26" fill="rgba(255,255,255,.5)" text-anchor="middle" font-weight="700">${safeTitle.charAt(0)}</text>
            </svg>
            <span class="ep-tag">${epLabel}</span>
        </div>
        <div class="hist-main">
            <div class="hist-title">${safeTitle}</div>
            <div class="hist-meta"><span>${isSeries ? '剧集' : '电影'}</span><span>·</span><span>${safeSource}</span></div>
            <div class="hist-progress">
                <div class="hist-bar"><i style="width:${pct}%"></i></div>
                <span class="hist-pct">${pct}%</span>
            </div>
        </div>
        <div class="hist-right">
            <span class="hist-time">${timeText}</span>
            <button class="hist-cta primary" onclick="event.stopPropagation(); playFromHistory('${esc(h.url || '')}', '${safeTitle}', ${h.episodeIndex || 0}, ${h.playbackPosition || 0})">
                <svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.14v13.72a1 1 0 0 0 1.52.85l11-6.86a1 1 0 0 0 0-1.7l-11-6.86A1 1 0 0 0 8 5.14z"/></svg>
                继续观看
            </button>
        </div>
    </div>`;
}

/* ---------- 视图切换 ---------- */
let currentView = 'home';

function switchView(view) {
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    const el = document.getElementById('view-' + view);
    if (!el) return;
    el.classList.add('active');
    currentView = view;
    // 顶栏导航状态：仅当前精确匹配的项激活
    document.querySelectorAll('.nav-link').forEach(n => {
        const isActive = view !== 'category'
            ? n.dataset.view === view
            : (n.dataset.view === 'category' && n.dataset.cat === curCat);
        n.classList.toggle('active', isActive);
    });
    setBottomNav(view === 'category' ? curCat : view);
    window.scrollTo({ top: 0 });
}

function setBottomNav(cat) {
    document.querySelectorAll('.bottom-nav button').forEach(b => b.classList.remove('active'));
    const target = cat === 'home' || cat === 'history'
        ? `.bottom-nav button[data-view="${cat}"]`
        : `.bottom-nav button[data-view="category"][data-cat="${cat}"]`;
    const el = document.querySelector(target);
    if (el) el.classList.add('active');
}

function goHome() {
    if (typeof resetSearchArea === 'function') resetSearchArea();
    switchView('home');
}

function goHistory() {
    switchView('history');
    renderHistoryPage();
}

/* ---------- 主题切换 ---------- */
function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('appTheme', theme); } catch (e) {}
    const dark = theme === 'dark';
    const sun = document.getElementById('icSun');
    const moon = document.getElementById('icMoon');
    if (sun) sun.style.display = dark ? 'block' : 'none';
    if (moon) moon.style.display = dark ? 'none' : 'block';
    const dm = document.getElementById('darkModeToggle');
    if (dm) dm.checked = dark;
}

function initTheme() {
    let theme = 'dark';
    try { theme = localStorage.getItem('appTheme') || 'dark'; } catch (e) {}
    applyTheme(theme);
    const btn = document.getElementById('themeBtn');
    if (btn) btn.addEventListener('click', () => {
        applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
    });
    const dm = document.getElementById('darkModeToggle');
    if (dm) dm.addEventListener('change', () => {
        applyTheme(dm.checked ? 'dark' : 'light');
    });
    // 默认倍速偏好
    const rateSelect = document.getElementById('defaultRateSelect');
    if (rateSelect) {
        try {
            const saved = localStorage.getItem('defaultPlaybackRate');
            if (saved) rateSelect.value = saved;
        } catch (e) {}
        rateSelect.addEventListener('change', () => {
            try { localStorage.setItem('defaultPlaybackRate', rateSelect.value); } catch (e) {}
            if (typeof showToast === 'function') showToast('默认倍速已保存', 'success');
        });
    }
}

/* ---------- 初始化 ---------- */
document.addEventListener('DOMContentLoaded', function () {
    initTheme();

    // 导航绑定
    const goHomeEl = document.getElementById('goHome');
    if (goHomeEl) goHomeEl.addEventListener('click', goHome);
    document.querySelectorAll('[data-view="home"]').forEach(el => el.addEventListener('click', goHome));
    document.querySelectorAll('[data-view="history"]').forEach(el => el.addEventListener('click', goHistory));
    document.querySelectorAll('[data-view="category"]').forEach(el => el.addEventListener('click', () => goCategory(el.dataset.cat)));

    // 行头"更多"跳分类
    document.querySelectorAll('.more[data-goto]').forEach(b => {
        b.addEventListener('click', () => goCategory(b.dataset.goto));
    });

    // 历史页筛选
    document.querySelectorAll('#view-history [data-hf]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('#view-history [data-hf]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            histPageFilter = chip.dataset.hf;
            renderHistoryPage();
        });
    });

    // 清空历史
    const clearHist = document.getElementById('btnClearHist');
    if (clearHist) clearHist.addEventListener('click', () => {
        if (confirm('确定要清空全部观看历史吗？此操作不可恢复。')) {
            try { localStorage.removeItem('viewingHistory'); } catch (e) {}
            renderHistoryPage();
            if (typeof showToast === 'function') showToast('观看历史已清空', 'success');
        }
    });

    // 加载更多
    const loadMoreBtn = document.getElementById('btnLoadMore');
    if (loadMoreBtn) loadMoreBtn.addEventListener('click', loadCategoryPage);

    // 首页内容（renderHomeRows 完成后触发 renderHero，复用行数据池）
    const heroBodyEl = document.getElementById('heroBody');
    if (heroBodyEl) heroBodyEl.innerHTML = `<div class="hero-loading"><div class="spin"></div><span style="margin-left:10px">正在加载推荐内容...</span></div>`;
    renderHomeRows();
});
