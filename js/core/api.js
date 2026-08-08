// 真实 API 请求封装（重构后）
// 旧版 api.js 是一个"假 API 层"：劫持 window.fetch 拦截 /api/* 请求，
// 在浏览器本地拼 /proxy/ 转发。重构后请求真实打到 Python 后端，
// 鉴权由 js/core/proxy-auth.js 的 fetch 拦截器自动附加 Authorization 头。

/**
 * 通用请求封装：请求 /api/* 并解包 {code, data, message}
 * 返回 data（顶层字段）；detail 等特殊扁平响应由调用方直接 fetch。
 */
const Api = {
    async request(path, params = {}, options = {}) {
        const qs = new URLSearchParams();
        Object.entries(params).forEach(([k, v]) => {
            if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
        });
        const url = path + (qs.toString() ? '?' + qs.toString() : '');
        const res = await fetch(url, options);
        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            throw new Error(data.message || ('请求失败: ' + res.status));
        }
        return data.data !== undefined ? data.data : data;
    },

    get(path, params) {
        return this.request(path, params);
    },

    post(path, body) {
        return this.request(path, {}, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
    },

    put(path, body) {
        return this.request(path, {}, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
    },

    del(path, params) {
        return this.request(path, params, { method: 'DELETE' });
    }
};

window.Api = Api;

/**
 * 站点可用性测试（设置面板"测试自定义接口"用）
 * 用自定义源走一次真实搜索，验证源可用。
 */
async function testSiteAvailability(apiUrl) {
    try {
        const response = await fetch(
            '/api/search?wd=test&source=custom&api_url=' + encodeURIComponent(apiUrl),
            { signal: AbortSignal.timeout(5000) }
        );
        if (!response.ok) return false;
        const data = await response.json();
        return data.code === 0 && Array.isArray(data.data.items);
    } catch (error) {
        console.error('站点可用性测试失败:', error);
        return false;
    }
}
