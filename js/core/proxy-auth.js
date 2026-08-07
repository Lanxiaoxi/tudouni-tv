/**
 * 登录态鉴权模块（重构后）
 *
 * 职责：
 * 1. token 管理：登录后由后端签发，存 localStorage，7 天有效
 * 2. fetch 拦截：所有 /api/* 请求自动附加 Authorization: Bearer <token>
 *
 * 替代旧版"sha256(密码) 拼 URL 参数"的鉴权方式：
 * 密码不再注入页面源码，token 由服务器签发，修复哈希公开与时间戳绕过两个漏洞。
 */

const AUTH_STORAGE_KEY = 'authToken';
let cachedToken = null;

function getToken() {
    if (cachedToken) return cachedToken;
    try { cachedToken = localStorage.getItem(AUTH_STORAGE_KEY); } catch (e) {}
    return cachedToken;
}

function setToken(token) {
    cachedToken = token;
    try {
        if (token) localStorage.setItem(AUTH_STORAGE_KEY, token);
        else localStorage.removeItem(AUTH_STORAGE_KEY);
    } catch (e) {}
}

function clearToken() {
    setToken(null);
}

// 登录：POST /api/auth，成功后保存 token
async function login(password) {
    const res = await fetch('/api/auth', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: password })
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.code !== 0 || !data.data || !data.data.token) {
        throw new Error(data.message || '登录失败');
    }
    setToken(data.data.token);
    return data.data.token;
}

// 包装 fetch：/api/* 自动附加 Authorization 头（原有调用方无需改动）
// 未登录时拦截 API 请求：不发请求、不抛错（返回 401 响应，调用方按失败降级），
// 并触发一次登录弹窗（节流，避免刷新风暴）
(function () {
    const originalFetch = window.fetch;
    let loginPrompted = false;

    function promptLogin() {
        if (!loginPrompted) {
            loginPrompted = true;
            document.dispatchEvent(new CustomEvent('requireLogin'));
            // 兜底：事件监听万一未注册，延时后直接调用全局函数弹窗
            setTimeout(function () {
                if (typeof window.showPasswordModal === 'function') {
                    try { window.showPasswordModal(); } catch (e) { console.error('弹窗异常:', e); }
                }
            }, 60);
        }
    }

    window.fetch = async function (input, init) {
        const url = typeof input === 'string' ? input : (input && input.url) || '';
        if (typeof url === 'string' && url.startsWith('/api/')) {
            const isAuthEndpoint = url.startsWith('/api/auth') || url.startsWith('/api/health');
            if (!isAuthEndpoint && !getToken()) {
                // 未登录：不发请求，返回 401 响应（登录成功后刷新页面重载数据）
                promptLogin();
                return Promise.resolve(new Response(
                    JSON.stringify({ code: 401, message: '需要登录' }),
                    { status: 401, headers: { 'Content-Type': 'application/json' } }
                ));
            }
            init = init || {};
            if (!(init.headers instanceof Headers)) {
                init.headers = new Headers(init.headers || {});
            }
            const token = getToken();
            if (token && !init.headers.has('Authorization')) {
                init.headers.set('Authorization', 'Bearer ' + token);
            }
            try {
                const resp = await originalFetch.call(this, input, init);
                // token 过期/失效：清除并提示重新登录
                if (resp.status === 401 && !isAuthEndpoint) {
                    clearToken();
                    promptLogin();
                }
                return resp;
            } catch (e) {
                throw e;
            }
        }
        return originalFetch.call(this, input, init);
    };
})();

// 导出到全局（保持旧调用约定，新代码用 window.Api）
window.ProxyAuth = {
    login,
    getToken,
    setToken,
    clearToken
};
