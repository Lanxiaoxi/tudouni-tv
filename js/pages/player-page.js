/* ============================================================
   player-page.js · 播放页增强（重构后新增）
   - 片名信息区：评分/类型/年份/地区/简介（/api/detail）
   - 猜你喜欢：聚合源最新内容推荐
   ============================================================ */

function ppEsc(s) {
    return String(s == null ? '' : s)
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/* ---------- 片名信息区 ---------- */
async function initWatchInfo() {
    const params = new URLSearchParams(window.location.search);
    const id = params.get('id');
    const source = params.get('source') || params.get('source_code') || '';

    // 标题兜底（player.js 已设置 videoTitle，这里同步大标题）
    const title = params.get('title') || localStorage.getItem('currentVideoTitle') || '';
    const watchTitle = document.getElementById('watchTitle');
    if (watchTitle && title) watchTitle.textContent = title;

    if (!id || !source) return;

    try {
        const res = await fetch(`/api/detail?id=${encodeURIComponent(id)}&source=${encodeURIComponent(source)}&_t=${Date.now()}`);
        const data = await res.json();
        const info = data && data.videoInfo;
        if (!info) return;

        // 标签
        const tags = [];
        if (info.type) tags.push(ppEsc(info.type));
        if (info.year) tags.push(ppEsc(info.year));
        if (info.area) tags.push(ppEsc(info.area));
        if (info.remarks) tags.push(ppEsc(info.remarks));
        if (info.source_name) tags.push(ppEsc(info.source_name));

        const tagBox = document.getElementById('watchTags');
        if (tagBox && tags.length) {
            tagBox.innerHTML = tags.map(t => `<span class="tag-pill">${t}</span>`).join('');
        }

        // 简介
        const desc = info.desc ? info.desc.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() : '';
        const descBox = document.getElementById('watchDesc');
        if (descBox && desc) {
            descBox.innerHTML = `<b>剧情简介</b>\n${ppEsc(desc)}`;
            descBox.style.display = 'block';
        }

        // 刷新播放按钮：点击刷新整个页面（重新加载当前视频）
        const playBtn = document.getElementById('btnPlayCurrent');
        if (playBtn && typeof currentEpisodeIndex !== 'undefined') {
            playBtn.style.display = 'inline-flex';
            playBtn.onclick = () => {
                window.location.reload();
            };
            if (currentEpisodes && currentEpisodes.length) {
                playBtn.querySelector('span').textContent = '刷新播放';
            }
        }
    } catch (e) {
        console.warn('播放页信息加载失败:', e.message);
    }
}

/* ---------- 猜你喜欢 ---------- */
async function ppFetchList(apiId) {
    try {
        const site = API_SITES[apiId];
        if (!site) return [];
        const data = await window.Api.get('/api/vodlist', { source: apiId, pg: 1 });
        return (data && data.items) || [];
    } catch (e) {
        return [];
    }
}

async function renderRelated() {
    const box = document.getElementById('relatedList');
    if (!box) return;
    const currentTitle = (localStorage.getItem('currentVideoTitle') || '');
    try {
        const keys = Object.keys(API_SITES).filter(k => !API_SITES[k].adult).slice(0, 3);
        const results = await Promise.all(keys.map(k => ppFetchList(k)));
        let items = [];
        results.forEach(r => { items = items.concat(r); });
        const seen = new Set();
        items = items.filter(it => {
            const k = it.vod_name || '';
            if (!k || k === currentTitle || seen.has(k)) return false;
            seen.add(k);
            return true;
        });
        const slice = items.slice(0, 8);
        if (!slice.length) {
            box.innerHTML = `<div style="color:var(--text-3);font-size:13px;padding:14px 0">暂无推荐内容</div>`;
            return;
        }
        box.innerHTML = slice.map((it, i) => {
            const safeName = ppEsc(it.vod_name || '未知视频');
            const cover = (it.vod_pic && (it.vod_pic.startsWith('http') || it.vod_pic.startsWith('/'))) ? it.vod_pic : '';
            const score = it.vod_score ? ppEsc(it.vod_score) : '';
            const year = it.vod_year ? ppEsc(it.vod_year) : '';
            const typeName = it.type_name ? ppEsc(it.type_name) : '';
            const remarks = it.vod_remarks ? ppEsc(it.vod_remarks) : '';
            const gid = 'relArt' + (it.vod_id || ('x' + i)) + '_' + i;
            const art = `<svg viewBox="0 0 80 120" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
                <defs><linearGradient id="${gid}" x1="0" y1="0" x2="1" y2="1">
                    <stop offset="0" stop-color="#1a2a6c"/><stop offset="1" stop-color="#2c3e50"/>
                </linearGradient></defs>
                <rect width="80" height="120" fill="url(#${gid})"/>
                <circle cx="80" cy="0" r="36" fill="#ffb020" opacity=".1"/>
                <text x="40" y="68" font-family="'Noto Sans SC',sans-serif" font-size="34" fill="rgba(255,255,255,.55)" text-anchor="middle" font-weight="700">${safeName.charAt(0)}</text>
            </svg>`;
            return `
            <div class="rel-card" onclick="ppOpenRelated('${(it.vod_id || '').toString().replace(/[^\w-]/g, '')}','${safeName.replace(/'/g, "\\'")}','${it.source_code || ''}')">
                <div class="rel-poster">
                    ${art}
                    ${cover ? `<img src="${ppEsc(cover)}" alt="${safeName}" loading="lazy" onerror="this.style.display='none'">` : ''}
                    ${score ? `<span class="score">${score}</span>` : ''}
                </div>
                <div class="rel-meta">
                    <div class="rel-title">${safeName}</div>
                    <div class="rel-sub">${year ? year + ' · ' : ''}${typeName || '影视'}</div>
                    ${remarks ? `<div class="rel-ep">${remarks}</div>` : ''}
                </div>
            </div>`;
        }).join('');
    } catch (e) {
        box.innerHTML = `<div style="color:var(--text-3);font-size:13px;padding:14px 0">推荐加载失败</div>`;
    }
}

async function ppOpenRelated(id, title, source) {
    if (!id || !source) return;
    if (typeof showLoading === 'function') showLoading('正在获取播放地址...');
    try {
        const res = await fetch(`/api/detail?id=${encodeURIComponent(id)}&source=${encodeURIComponent(source)}&_t=${Date.now()}`);
        const data = await res.json();
        if (data && data.episodes && data.episodes.length) {
            const url = data.episodes[0];
            localStorage.setItem('currentVideoTitle', title);
            localStorage.setItem('currentEpisodes', JSON.stringify(data.episodes));
            localStorage.setItem('currentEpisodeIndex', 0);
            localStorage.setItem('currentSourceCode', source);
            localStorage.setItem('lastPlayTime', Date.now());
            localStorage.setItem('lastPageUrl', window.location.href);
            window.location.href = `watch.html?id=${encodeURIComponent(id)}&source=${encodeURIComponent(source)}&url=${encodeURIComponent(url)}&index=0&title=${encodeURIComponent(title)}&back=${encodeURIComponent(window.location.href)}`;
        } else {
            if (typeof showToast === 'function') showToast('该视频暂无可用播放源', 'warning');
        }
    } catch (e) {
        if (typeof showToast === 'function') showToast('获取播放地址失败', 'error');
    } finally {
        if (typeof hideLoading === 'function') hideLoading();
    }
}
window.ppOpenRelated = ppOpenRelated;

/* ---------- 初始化 ---------- */
document.addEventListener('DOMContentLoaded', function () {
    // 等待 player.js 初始化完成（passwordVerified 事件后会初始化页面内容）
    const boot = () => {
        initWatchInfo();
        renderRelated();
    };
    // player.js 在密码验证后才初始化，这里延迟执行避免读取空数据
    setTimeout(boot, 400);
});
