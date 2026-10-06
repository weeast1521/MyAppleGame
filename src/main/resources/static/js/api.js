'use strict';

/* ================================================================
 * 공통 헬퍼 + API 클라이언트
 *  - 모든 REST 응답은 CustomResponse { isSuccess, code, message, result }
 *  - accessToken 은 Authorization: Bearer 헤더로 전달
 *  - 401 응답 시 refreshToken 으로 재발급(/api/auth/reissue) 후 1회 재시도
 *  - 재발급은 동시에 하나만(single-flight) — refresh는 회전되므로 같은 토큰으로 두 번 쓰면 두 번째가 실패한다
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
    const sentToken = auth ? Auth.accessToken : null;
    if (sentToken) headers['Authorization'] = `Bearer ${sentToken}`;

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
        // 요청이 오가는 사이 다른 호출이 이미 재발급했다면 새 토큰으로 다시 보내기만 한다
        // (Promise.all로 동시에 나간 요청 중 늦게 401을 받은 쪽 — 재발급을 또 하면 refresh만 한 번 더 회전된다)
        const result = Auth.accessToken !== sentToken ? 'OK' : await tryReissue();
        if (result === 'OK') {
            return apiFetch(path, { method, body, auth, _retried: true });
        }
        if (result === 'NETWORK') {
            // 서버에 못 닿은 것이지 refresh가 거절된 게 아니다 — 로그아웃시키지 않는다
            throw new ApiError('NETWORK', '서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.', 0);
        }
        expireSession();
        throw new ApiError('AUTH401_3', '로그인이 만료되었습니다. 다시 로그인해주세요.', 401);
    }

    // nginx가 빈도 제한으로 돌려보낸 429 — 본문이 HTML이라 CustomResponse 해석이 안 된다 (#58).
    // 앱에는 도달하지 않았으므로 재시도하면 통과할 수 있다는 점에서 다른 4xx와 다르다.
    if (res.status === 429) {
        throw new ApiError('HTTP429', '요청이 너무 많습니다. 잠시 후 다시 시도해주세요.', 429);
    }

    let payload = null;
    try { payload = await res.json(); } catch { /* 본문 없는 응답 */ }

    if (!res.ok || (payload && payload.isSuccess === false)) {
        const code = (payload && payload.code) || `HTTP${res.status}`;
        // @Valid 실패(COMMON400_1)는 공통 message("요청 데이터 검증에 실패했습니다")가 아니라
        // result 에 담긴 필드별 사유("비밀번호는 8자 이상 …")를 보여준다 — 사용자가 무엇을 고칠지 알아야 다음 시도를 한다
        const fieldMessages = validationMessages(payload);
        const message = fieldMessages || (payload && payload.message) || `요청 실패 (HTTP ${res.status})`;
        throw new ApiError(code, message, res.status);
    }
    return payload ? payload.result : null;
}

/*
 * GlobalExceptionHandler 가 검증 실패를 { code: 'COMMON400_1', result: { 필드: 사유, … } } 로 내려준다.
 * 사유들을 한 줄로 잇는다. 필드가 여럿이면 전부 — 한 번에 다 고치게. 검증 실패가 아니면 null.
 */
function validationMessages(payload) {
    if (!payload || payload.code !== 'COMMON400_1' || !payload.result || typeof payload.result !== 'object') return null;
    const msgs = Object.values(payload.result).filter((m) => typeof m === 'string' && m);
    return msgs.length ? msgs.join(' ') : null;
}

/* refresh까지 거절됐을 때 — 저장된 인증을 지우고 화면 전환은 app.js의 auth:expired 핸들러에 맡긴다 */
function expireSession() {
    Auth.clear();
    window.dispatchEvent(new Event('auth:expired'));
}

/*
 * 재발급 결과: 'OK' | 'REJECTED'(refresh 만료·폐기 → 재로그인) | 'NETWORK'(서버에 못 닿음 → 재시도 여지)
 *
 * single-flight: 진행 중인 재발급이 있으면 새로 요청하지 않고 같은 Promise를 돌려준다.
 * refresh는 쓰는 순간 회전(폐기)되므로, 동시에 401을 받은 호출들이 각자 재발급하면
 * 첫 번째만 성공하고 나머지는 이미 폐기된 refresh를 보내 REJECTED → 강제 로그아웃이 된다.
 */
let reissueInFlight = null;

function tryReissue() {
    if (!reissueInFlight) {
        reissueInFlight = reissueOnce().finally(() => { reissueInFlight = null; });
    }
    return reissueInFlight;
}

async function reissueOnce() {
    const refreshToken = Auth.refreshToken;
    if (!refreshToken) return 'REJECTED';

    let res;
    try {
        res = await fetch('/api/auth/reissue', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ refreshToken }),
        });
    } catch {
        return 'NETWORK';
    }
    // 배포 전환 중 nginx가 502/503을 줄 수 있고, 빈도 제한이면 429다(#58) — 둘 다 서버가 refresh를
    // 판단한 응답이 아니다. REJECTED로 보면 멀쩡한 refresh를 버리고 로그아웃시킨다.
    if (res.status >= 500 || res.status === 429) return 'NETWORK';

    let payload = null;
    try { payload = await res.json(); } catch { /* 본문 없음 */ }
    if (!res.ok || !payload || payload.isSuccess === false) return 'REJECTED';

    Auth.saveTokens(payload.result);
    return 'OK';
}

/* 액세스 토큰의 payload(exp 등). 서명 검증은 서버 몫 — 여기서는 "갱신이 필요한가" 판단 힌트로만 쓴다 */
function accessTokenClaims() {
    const token = Auth.accessToken;
    if (!token) return null;
    try {
        const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        return JSON.parse(atob(b64));
    } catch {
        return null;
    }
}

// 만료까지 이보다 적게 남았으면 미리 재발급 — 연결 직후 만료되는 경계를 피한다
const ACCESS_REFRESH_MARGIN_SEC = 60;

/*
 * apiFetch를 거치지 않는 곳(STOMP CONNECT)에서 쓸 access를 확보한다. 결과는 tryReissue와 같은 세 갈래.
 * REST는 401 → 재발급 경로가 있지만, STOMP CONNECT의 거절은 ERROR 프레임이라 그 경로를 타지 않는다.
 * force: exp는 남았는데 서버가 거절한 경우(폐기된 토큰, 서버 정책 변경 등) — exp와 무관하게 재발급
 */
function ensureFreshAccessToken({ force = false } = {}) {
    const claims = accessTokenClaims();
    const secondsLeft = claims && claims.exp ? claims.exp - Date.now() / 1000 : -1;
    if (!force && secondsLeft > ACCESS_REFRESH_MARGIN_SEC) return Promise.resolve('OK');
    return tryReissue();
}
