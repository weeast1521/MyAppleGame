'use strict';

/* ================================================================
 * 공통 헬퍼 + API 클라이언트
 *  - 모든 REST 응답은 CustomResponse { isSuccess, code, message, result }
 *  - accessToken 은 Authorization: Bearer 헤더로 전달
 *  - 401 응답 시 refreshToken 으로 재발급(/api/auth/reissue) 후 1회 재시도
 * ================================================================ */

const $ = (id) => document.getElementById(id);

function escapeHtml(s) {
    return String(s ?? '').replace(/[&<>"']/g, (m) => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[m]
    ));
}

function fmtDate(iso) {
    return iso ? iso.replace('T', ' ').slice(0, 16) : '-';
}

function renderTimer(el, sec) {
    if (sec === null || sec === undefined) {
        el.textContent = '--';
        el.classList.remove('warn');
        return;
    }
    sec = Math.max(0, sec);
    const m = String(Math.floor(sec / 60)).padStart(2, '0');
    const s = String(sec % 60).padStart(2, '0');
    el.textContent = `${m}:${s}`;
    el.classList.toggle('warn', sec <= 10);
}

/* ---------------- 토큰/유저 저장소 ---------------- */
const Auth = {
    get accessToken() { return localStorage.getItem('accessToken'); },
    get refreshToken() { return localStorage.getItem('refreshToken'); },
    get user() {
        try { return JSON.parse(localStorage.getItem('user')); } catch { return null; }
    },
    get isLoggedIn() { return !!this.accessToken; },
    saveTokens({ accessToken, refreshToken }) {
        if (accessToken) localStorage.setItem('accessToken', accessToken);
        if (refreshToken) localStorage.setItem('refreshToken', refreshToken);
    },
    saveUser(user) { localStorage.setItem('user', JSON.stringify(user)); },
    clear() {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        localStorage.removeItem('user');
    },
};

class ApiError extends Error {
    constructor(code, message, status) {
        super(message);
        this.code = code;
        this.status = status;
    }
}

/* CustomResponse 를 해석해 result 만 돌려준다. 실패 시 ApiError throw. */
async function apiFetch(path, { method = 'GET', body, auth = true, _retried = false } = {}) {
    const headers = {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (auth && Auth.accessToken) headers['Authorization'] = `Bearer ${Auth.accessToken}`;

    let res;
    try {
        res = await fetch(path, {
            method,
            headers,
            body: body !== undefined ? JSON.stringify(body) : undefined,
        });
    } catch {
        throw new ApiError('NETWORK', '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.', 0);
    }

    // 액세스 토큰 만료 → 재발급 후 1회 재시도
    if (res.status === 401 && auth && Auth.refreshToken && !_retried) {
        if (await tryReissue()) {
            return apiFetch(path, { method, body, auth, _retried: true });
        }
        Auth.clear();
        window.dispatchEvent(new Event('auth:expired'));
        throw new ApiError('AUTH4013', '로그인이 만료되었습니다. 다시 로그인해주세요.', 401);
    }

    let payload = null;
    try { payload = await res.json(); } catch { /* 본문 없는 응답 */ }

    if (!res.ok || (payload && payload.isSuccess === false)) {
        const code = (payload && payload.code) || `HTTP${res.status}`;
        const message = (payload && payload.message) || `요청 실패 (HTTP ${res.status})`;
        throw new ApiError(code, message, res.status);
    }
    return payload ? payload.result : null;
}

async function tryReissue() {
    try {
        const res = await fetch('/api/auth/reissue', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken: Auth.refreshToken }),
        });
        const payload = await res.json();
        if (!res.ok || payload.isSuccess === false) return false;
        Auth.saveTokens(payload.result);
        return true;
    } catch {
        return false;
    }
}
