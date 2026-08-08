// 密码保护功能（重构后：登录态由后端 /api/auth 校验，前端不再持有密码哈希）

/**
 * 检查是否设置了密码保护
 * 重构后：密码由后端 PASSWORD 环境变量配置，前端一律视为已启用
 */
function isPasswordProtected() {
    return true;
}

/**
 * 检查是否强制要求设置密码
 * 重构后：密码在服务器 .env 配置，前端无需也无法设置，恒为 false
 */
function isPasswordRequired() {
    return false;
}

/**
 * 强制密码保护检查 - 防止绕过
 * 在关键操作前都应该调用此函数
 */
function ensurePasswordProtection() {
    if (isPasswordRequired()) {
        showPasswordModal();
        throw new Error('Password protection is required');
    }
    if (isPasswordProtected() && !isPasswordVerified()) {
        showPasswordModal();
        throw new Error('Password verification required');
    }
    return true;
}

window.isPasswordProtected = isPasswordProtected;
window.isPasswordRequired = isPasswordRequired;

/**
 * 验证登录（多用户版）：调后端 /api/auth/login 签发 token
 */
async function verifyPassword(username, password, mode) {
    try {
        const result = mode === 'register'
            ? await window.ProxyAuth.register(username, password)
            : await window.ProxyAuth.login(username, password);
        if (!result || !result.token) return false;

        localStorage.setItem(PASSWORD_CONFIG.localStorageKey, JSON.stringify({
            verified: true,
            timestamp: Date.now()
        }));

        // 登录成功后拉取服务端设置，覆盖本地（换设备恢复源勾选/偏好）
        try {
            if (typeof applyServerSettings === 'function') {
                await applyServerSettings();
            }
        } catch (e) {}

        return true;
    } catch (error) {
        console.error('登录失败:', error);
        return false;
    }
}

// 验证状态检查（重构后：有有效 token 即视为已登录）
function isPasswordVerified() {
    try {
        return !!window.ProxyAuth.getToken();
    } catch (error) {
        console.error('检查登录状态时出错:', error);
        return false;
    }
}

// 更新全局导出
window.isPasswordProtected = isPasswordProtected;
window.isPasswordRequired = isPasswordRequired;
window.isPasswordVerified = isPasswordVerified;
window.verifyPassword = verifyPassword;
window.ensurePasswordProtection = ensurePasswordProtection;
window.switchAuthTab = switchAuthTab;

// SHA-256实现，可用Web Crypto API
async function sha256(message) {
    if (window.crypto && crypto.subtle && crypto.subtle.digest) {
        const msgBuffer = new TextEncoder().encode(message);
        const hashBuffer = await crypto.subtle.digest('SHA-256', msgBuffer);
        const hashArray = Array.from(new Uint8Array(hashBuffer));
        return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
    }
    // HTTP 下调用原始 js‑sha256
    if (typeof window._jsSha256 === 'function') {
        return window._jsSha256(message);
    }
    throw new Error('No SHA-256 implementation available.');
}

/**
 * 显示密码验证弹窗
 */
function showPasswordModal() {
    // 弹窗必须盖过一切：隐藏 loading 遮罩（z-index 200）+ 弹窗提升到 300
    const loadingEl = document.getElementById('loading');
    if (loadingEl) loadingEl.style.display = 'none';
    const passwordModal = document.getElementById('passwordModal');
    if (passwordModal) {
        // 关键：.hidden 是 display:none !important，必须移除该类才能显示（style 内联压不过 !important）
        passwordModal.classList.remove('hidden');
        passwordModal.classList.add('show');
        passwordModal.style.zIndex = '300';
        // 防止出现豆瓣区域滚动条（元素可能缺失，做空值保护）
        const dbArea = document.getElementById('doubanArea');
        if (dbArea) dbArea.classList.add('hidden');
        const cancelBtn = document.getElementById('passwordCancelBtn');
        if (cancelBtn) cancelBtn.classList.add('hidden');

        passwordModal.style.display = 'flex';

        // 聚焦用户名输入框
        setTimeout(() => {
            const usernameInput = document.getElementById('authUsername');
            if (usernameInput) {
                usernameInput.focus();
            }
        }, 100);
    }
}

/**
 * 隐藏密码验证弹窗
 */
function hidePasswordModal() {
    const passwordModal = document.getElementById('passwordModal');
    if (passwordModal) {
        // 恢复 hidden 类（display:none !important）
        passwordModal.classList.add('hidden');
        passwordModal.classList.remove('show');
        passwordModal.style.display = 'none';

        // 隐藏密码错误提示
        hidePasswordError();

        // 清空密码输入框
        const passwordInput = document.getElementById('passwordInput');
        if (passwordInput) passwordInput.value = '';

        passwordModal.style.display = 'none';

        // 如果启用豆瓣区域则显示豆瓣区域
        if (localStorage.getItem('doubanEnabled') === 'true') {
            document.getElementById('doubanArea').classList.remove('hidden');
            initDouban();
        }
    }
}

/**
 * 显示密码错误信息
 */
function showPasswordError() {
    const errorElement = document.getElementById('passwordError');
    if (errorElement) {
        errorElement.classList.remove('hidden');
    }
}

/**
 * 隐藏密码错误信息
 */
function hidePasswordError() {
    const errorElement = document.getElementById('passwordError');
    if (errorElement) {
        errorElement.classList.add('hidden');
    }
}

/**
 * 处理登录/注册提交事件（异步）
 */
async function handlePasswordSubmit() {
    const usernameInput = document.getElementById('authUsername');
    const passwordInput = document.getElementById('passwordInput');
    const username = usernameInput ? usernameInput.value.trim() : '';
    const password = passwordInput ? passwordInput.value.trim() : '';
    const mode = window._authMode === 'register' ? 'register' : 'login';

    if (!username || !password) {
        showPasswordError();
        const errEl = document.getElementById('passwordError');
        if (errEl) errEl.textContent = '请输入用户名和密码';
        return;
    }

    if (await verifyPassword(username, password, mode)) {
        hidePasswordModal();

        // 触发密码验证成功事件
        document.dispatchEvent(new CustomEvent('passwordVerified'));

        // 刷新页面：让全部数据请求带上新 token 重新加载
        location.reload();
    } else {
        showPasswordError();
        if (passwordInput) {
            passwordInput.value = '';
            passwordInput.focus();
        }
    }
}

/**
 * 切换登录/注册 Tab
 */
function switchAuthTab(mode) {
    window._authMode = mode;
    const tabLogin = document.getElementById('authTabLogin');
    const tabRegister = document.getElementById('authTabRegister');
    const btn = document.getElementById('passwordSubmitBtn');
    const desc = document.getElementById('authDesc');
    const err = document.getElementById('passwordError');

    if (tabLogin) tabLogin.classList.toggle('active', mode === 'login');
    if (tabRegister) tabRegister.classList.toggle('active', mode === 'register');
    if (btn) btn.textContent = mode === 'register' ? '注册' : '登录';
    if (desc) desc.textContent = mode === 'register' ? '注册新账号，观看记录将保存到服务器' : '登录后同步你的观看记录';
    if (err) err.classList.add('hidden');
}

/**
 * 初始化登录系统
 */
function initPasswordProtection() {
    // 未登录则显示登录/注册弹窗
    if (!isPasswordVerified()) {
        showPasswordModal();
    }
}

// 在页面加载完成后初始化密码保护
document.addEventListener('DOMContentLoaded', function () {
    // 先注册 requireLogin 兜底监听（任意未登录的 API 请求被拦截时触发弹窗）
    document.addEventListener('requireLogin', function () {
        try { showPasswordModal(); } catch (e) { console.error('弹窗异常:', e); }
    });

    try {
        initPasswordProtection();
    } catch (e) {
        console.error('密码保护初始化异常:', e);
        showPasswordModal();
    }
});