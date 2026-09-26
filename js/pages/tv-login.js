/**
 * 扫码登录 TV 端 · 手机确认页逻辑
 *
 * 页面：tv.html?code=<user_code>
 * 流程：
 *   1. 无 code → 让用户手输（电视上显示的 8 位确认码）
 *   2. 有 code 但手机未登录 → 先登录（ProxyAuth.login），登录成功即自动确认
 *   3. 有 code 且已登录 → 展示确认码让用户核对 → 确认 / 拒绝
 *
 * 依赖（均由 tv.html 按顺序引入）：
 *   - window.ProxyAuth（js/core/proxy-auth.js）：login / getToken / clearToken / getCurrentUsername
 *   - window.Api（js/core/api.js）：post 封装，非 2xx 时抛 Error(后端 message)
 *
 * 注意：本页**不加载** js/core/password.js——它的 DOMContentLoaded 会无条件弹全局登录框，
 * 会顶掉本页界面。登录表单由本页自行渲染。
 */

(function () {
    'use strict';

    // ---------- DOM ----------
    const sections = {
        noCode: document.getElementById('stateNoCode'),
        login: document.getElementById('stateLogin'),
        confirm: document.getElementById('stateConfirm'),
        done: document.getElementById('stateDone'),
    };
    const errorEl = document.getElementById('tvError');
    const codeInput = document.getElementById('codeInput');
    const codeForm = document.getElementById('codeForm');
    const loginForm = document.getElementById('loginForm');
    const loginBtn = document.getElementById('loginBtn');
    const codeEcho = document.getElementById('codeEcho');
    const loginCodeEcho = document.getElementById('loginCodeEcho');
    const accountName = document.getElementById('accountName');
    const confirmBtn = document.getElementById('confirmBtn');
    const denyBtn = document.getElementById('denyBtn');
    const doneIcon = document.getElementById('doneIcon');
    const doneTitle = document.getElementById('doneTitle');
    const doneDesc = document.getElementById('doneDesc');
    const doneActions = document.getElementById('doneActions');

    /** 当前确认码（规范化为大写、去分隔符）。 */
    let currentCode = '';

    // ---------- 工具 ----------

    /** 界面只显示一个 section。 */
    function show(name) {
        Object.keys(sections).forEach(function (key) {
            const el = sections[key];
            if (!el) return;
            if (key === name) el.classList.remove('hidden');
            else el.classList.add('hidden');
        });
        hideError();
    }

    function showError(msg) {
        if (!errorEl) return;
        errorEl.textContent = msg || '';
        if (msg) errorEl.classList.remove('hidden');
        else errorEl.classList.add('hidden');
    }

    function hideError() {
        showError('');
    }

    /** 用户可能手输带空格/连字符，统一成大写连续字符。 */
    function normalizeCode(raw) {
        return String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    }

    function setBusy(btn, busy, busyText, idleText) {
        if (!btn) return;
        btn.disabled = !!busy;
        btn.textContent = busy ? busyText : idleText;
    }

    /**
     * 取展示给用户的错误文案。
     * Api.request 在非 2xx 时抛出 Error(后端 message)，后端返回的正是中文可读文案
     * （如「确认码已过期，请在电视上重新获取」），故直接透传；无 message 时用 fallback。
     */
    function friendlyError(err, fallback) {
        const msg = err && err.message ? String(err.message) : '';
        return msg || fallback;
    }

    // ---------- 主流程 ----------

    function boot() {
        const params = new URLSearchParams(window.location.search);
        currentCode = normalizeCode(params.get('code'));
        if (currentCode) {
            // URL 带码：清理地址栏（避免用户把带码链接分享出去），再继续
            try {
                window.history.replaceState({}, '', window.location.pathname);
            } catch (e) {}
            routeWithCode();
        } else {
            show('noCode');
            if (codeInput) codeInput.focus();
        }
    }

    /** 有确认码时：按手机端登录态决定展示登录表单还是确认界面。 */
    function routeWithCode() {
        if (loginCodeEcho) loginCodeEcho.textContent = currentCode;
        if (codeEcho) codeEcho.textContent = currentCode;

        const token = window.ProxyAuth ? window.ProxyAuth.getToken() : null;
        if (!token) {
            show('login');
            const nu = document.getElementById('usernameInput');
            if (nu) nu.focus();
            return;
        }
        // 本地有 token 不代表服务端仍认（可能已过期/被吊销）。
        // /api/me 若返回 401，proxy-auth 拦截器会清掉 token 并弹它自己的全局登录框——
        // 但本页没加载 password.js，所以那里只会触发一个空监听，无副作用。
        verifyTokenThenConfirm();
    }

    async function verifyTokenThenConfirm() {
        try {
            const me = await window.Api.get('/api/me');
            const name = (me && me.username) || window.ProxyAuth.getCurrentUsername();
            if (accountName) accountName.textContent = name || '当前账号';
            show('confirm');
            if (confirmBtn) confirmBtn.focus();
        } catch (e) {
            // token 失效：清掉本地凭证，退回登录表单（本页自行处理，不依赖全局弹窗）
            try { window.ProxyAuth.clearToken(); } catch (err) {}
            show('login');
            showError('登录状态已失效，请重新登录后再确认');
        }
    }

    // ---------- 事件绑定 ----------

    // 1) 手输确认码
    if (codeForm) {
        codeForm.addEventListener('submit', function (e) {
            e.preventDefault();
            const code = normalizeCode(codeInput ? codeInput.value : '');
            if (code.length !== 8) {
                showError('请输入电视上显示的 8 位确认码');
                return;
            }
            currentCode = code;
            routeWithCode();
        });
    }

    // 2) 登录（登录成功后自动继续确认）
    if (loginForm) {
        loginForm.addEventListener('submit', async function (e) {
            e.preventDefault();
            const usernameEl = document.getElementById('usernameInput');
            const passwordEl = document.getElementById('passwordInput');
            const username = usernameEl ? usernameEl.value.trim() : '';
            const password = passwordEl ? passwordEl.value : '';
            if (!username || !password) {
                showError('请输入用户名和密码');
                return;
            }
            setBusy(loginBtn, true, '登录中…', '登录并授权');
            hideError();
            try {
                await window.ProxyAuth.login(username, password);
                setBusy(loginBtn, false, '', '登录并授权');
                // 登录成功 → 直接确认，省掉一次点击（fromLogin=true：确认失败时不逼用户重输密码）
                await doConfirm(true);
            } catch (err) {
                setBusy(loginBtn, false, '', '登录并授权');
                showError(friendlyError(err, '登录失败，请检查用户名和密码'));
            }
        });
    }

    // 3) 确认授权
    if (confirmBtn) {
        confirmBtn.addEventListener('click', function () {
            doConfirm();
        });
    }

    /**
     * 提交确认。
     * @param fromLogin 是否是「刚登录成功，顺带确认」这条路径——此时确认失败不必让用户重输密码
     */
    async function doConfirm(fromLogin) {
        setBusy(confirmBtn, true, '确认中…', '确认登录');
        hideError();
        try {
            await window.Api.post('/api/auth/device/confirm', { user_code: currentCode });
            showDone(true, '授权成功', '电视端已登录，可以开始观看了。');
        } catch (err) {
            const msg = friendlyError(err, '确认失败，请重试');
            // 码已死（无效/过期/已被使用）：重试无意义，给一个明确的下一步
            if (/无效|过期|已被使用/.test(msg)) {
                showDone(
                    false,
                    '确认码已失效',
                    msg + ' 请回到电视端，用新显示的确认码重新扫描。',
                    '重新输入确认码',
                    'tv.html'
                );
                return;
            }
            // 已登录只是确认没成（网络抖动等）：切到确认界面直接重试，不必再输密码
            if (fromLogin) await verifyTokenThenConfirm();
            showError(msg);
        } finally {
            setBusy(confirmBtn, false, '', '确认登录');
        }
    }

    // 4) 拒绝授权
    if (denyBtn) {
        denyBtn.addEventListener('click', async function () {
            setBusy(denyBtn, true, '处理中…', '不是我操作的');
            try {
                await window.Api.post('/api/auth/device/deny', { user_code: currentCode });
                showDone(false, '已拒绝', '电视端未登录。如非本人操作，建议修改账号密码。');
            } catch (err) {
                setBusy(denyBtn, false, '', '不是我操作的');
                showError(friendlyError(err, '操作失败，请重试'));
            }
        });
    }

    function showDone(success, title, desc, actionLabel, actionHref) {
        if (doneIcon) {
            doneIcon.textContent = success ? '✓' : '✕';
            doneIcon.classList.toggle('is-error', !success);
        }
        if (doneTitle) doneTitle.textContent = title;
        if (doneDesc) doneDesc.textContent = desc;
        if (doneActions) {
            doneActions.innerHTML = '';
            const back = document.createElement('button');
            back.className = 'act-btn primary tv-btn';
            back.textContent = actionLabel || '返回首页';
            const href = actionHref || 'index.html';
            back.addEventListener('click', function () {
                window.location.href = href;
            });
            doneActions.appendChild(back);
        }
        show('done');
    }

    // ---------- 启动 ----------
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', boot);
    } else {
        boot();
    }
})();
