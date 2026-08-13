'use strict';

/* ================================================================
 * 화면 전환 + 인증 + 랭킹/내 기록/내 정보 탭
 * ================================================================ */

/* ---------------- 화면/탭 전환 ---------------- */
function showView(name) {
    $('view-auth').classList.toggle('hidden', name !== 'auth');
    $('view-home').classList.toggle('hidden', name !== 'home');
}

function showTab(name) {
    document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    document.querySelectorAll('.tabPanel').forEach((p) => p.classList.toggle('hidden', p.id !== `tab-${name}`));
    if (name === 'ranking') Ranking.load();
    if (name === 'records') Records.load();
    if (name === 'profile') Profile.load();
}

function enterHome() {
    $('headerNickname').textContent = Auth.user?.nickname ?? '';
    showView('home');
    showTab('solo');
    // 서버와 유저 정보 동기화 (백엔드 미구현/네트워크 오류는 조용히 무시)
    apiFetch('/api/users/me')
        .then((me) => {
            Auth.saveUser({ ...Auth.user, ...me });
            $('headerNickname').textContent = me.nickname;
        })
        .catch(() => {});
}

/* ---------------- 인증 ---------------- */
function authError(msg) { $('authMsg').textContent = msg; }

function bindAuth() {
    // 로그인 ↔ 회원가입 폼 전환
    $('authSwitchBtn').addEventListener('click', (e) => {
        e.preventDefault();
        const showSignup = $('signupForm').classList.contains('hidden');
        $('signupForm').classList.toggle('hidden', !showSignup);
        $('loginForm').classList.toggle('hidden', showSignup);
        $('authSwitchText').textContent = showSignup ? '이미 계정이 있나요?' : '계정이 없나요?';
        $('authSwitchBtn').textContent = showSignup ? '로그인' : '회원가입';
        authError('');
    });

    $('loginForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        authError('');
        try {
            const r = await apiFetch('/api/auth/login', {
                method: 'POST',
                auth: false,
                body: { email: $('loginEmail').value.trim(), password: $('loginPassword').value },
            });
            Auth.saveTokens(r);
            Auth.saveUser(r.user);
            enterHome();
        } catch (err) {
            authError(err.message);
        }
    });

    $('signupForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        authError('');
        try {
            const r = await apiFetch('/api/auth/signup', {
                method: 'POST',
                auth: false,
                body: {
                    email: $('signupEmail').value.trim(),
                    password: $('signupPassword').value,
                    nickname: $('signupNickname').value.trim(),
                },
            });
            // 가입 성공 → 로그인 폼으로 전환 + 이메일 미리 채움
            $('authSwitchBtn').click();
            $('loginEmail').value = r?.email ?? $('signupEmail').value.trim();
            authError('가입 완료! 로그인해주세요.');
        } catch (err) {
            authError(err.message);
        }
    });

    $('btnKakao').addEventListener('click', () => {
        const { clientId, redirectUri } = OAUTH_CONFIG.kakao;
        if (!clientId) return authError('js/config.js 에 카카오 REST API 키를 설정하세요.');
        location.href = 'https://kauth.kakao.com/oauth/authorize'
            + `?client_id=${clientId}`
            + `&redirect_uri=${encodeURIComponent(redirectUri)}`
            + '&response_type=code';
    });

    $('btnNaver').addEventListener('click', () => {
        const { clientId, redirectUri } = OAUTH_CONFIG.naver;
        if (!clientId) return authError('js/config.js 에 네이버 Client ID를 설정하세요.');
        const state = crypto.randomUUID();
        sessionStorage.setItem('naver_oauth_state', state);
        location.href = 'https://nid.naver.com/oauth2.0/authorize'
            + `?client_id=${clientId}`
            + `&redirect_uri=${encodeURIComponent(redirectUri)}`
            + '&response_type=code'
            + `&state=${state}`;
    });

    $('btnLogout').addEventListener('click', async () => {
        try { await apiFetch('/api/auth/logout', { method: 'POST' }); } catch { /* 무시 */ }
        Battle.cleanup();
        Auth.clear();
        showView('auth');
        authError('');
    });
}

/* ---------------- 랭킹 탭 ---------------- */
const Ranking = {
    period: 'alltime',
    rows: [],        // 한 번에 받아둔 최대 100등 스냅샷
    shown: 0,        // 현재 화면에 펼친 개수
    PAGE: 20,

    bind() {
        document.querySelectorAll('.seg').forEach((b) => {
            b.addEventListener('click', () => {
                this.period = b.dataset.period;
                document.querySelectorAll('.seg').forEach((x) => x.classList.toggle('active', x === b));
                this.load();
            });
        });
        $('btnMoreRanking').addEventListener('click', () => this.reveal());
    },

    async load() {
        $('rankingMsg').textContent = '';
        try {
            // 100등까지 한 번에 받는다 — 순위는 요청 사이에 재배열될 수 있어
            // offset 페이징으로 나눠 받으면 중복/누락이 생기므로 스냅샷 방식이 정확하다
            const r = await apiFetch(`/api/rankings/solo?period=${this.period}&offset=0&size=100`);
            $('rankingSource').textContent = r.source ?? '-';
            if (r.myRank) {
                $('myRank').classList.remove('hidden');
                $('myRank').textContent = `내 순위: ${r.myRank.rank}위 (${r.myRank.score}점)`;
            } else {
                $('myRank').classList.add('hidden');
            }
            this.rows = r.rankings ?? [];
            this.shown = 0;
            $('rankingBody').innerHTML = this.rows.length ? '' : '<tr><td colspan="3">아직 기록이 없습니다.</td></tr>';
            this.reveal();   // 첫 20등 펼치기
        } catch (e) {
            $('rankingBody').innerHTML = '';
            $('rankingMsg').textContent = e.message;
        }
    },

    // 더보기 — 서버 요청 없이 받아둔 스냅샷을 20등씩 펼친다
    reveal() {
        const next = this.rows.slice(this.shown, this.shown + this.PAGE);
        $('rankingBody').insertAdjacentHTML('beforeend', next.map((row) => `
            <tr>
                <td>${row.rank}</td>
                <td>${escapeHtml(row.nickname)}</td>
                <td>${row.score}</td>
            </tr>`).join(''));
        this.shown += next.length;
        $('btnMoreRanking').classList.toggle('hidden', this.shown >= this.rows.length);
    },
};

/* ---------------- 내 기록 탭 ---------------- */
const Records = {
    recordsCursor: null,
    matchesCursor: null,

    bind() {
        $('btnMoreRecords').addEventListener('click', () => this.loadRecords(true));
        $('btnMoreMatches').addEventListener('click', () => this.loadMatches(true));
    },

    async load() {
        $('recordsMsg').textContent = '';
        this.recordsCursor = null;
        this.matchesCursor = null;
        $('recordsBody').innerHTML = '';
        $('matchesBody').innerHTML = '';
        await Promise.all([this.loadSummary(), this.loadRecords(false), this.loadMatches(false)]);
    },

    async loadSummary() {
        try {
            const s = await apiFetch('/api/solo/records/me/summary');
            $('recordSummary').innerHTML = `
                <div class="stat"><div class="k">최고 점수</div><div class="v">${s.bestScore ?? '-'}</div></div>
                <div class="stat"><div class="k">총 게임 수</div><div class="v">${s.totalGames ?? 0}</div></div>
                <div class="stat"><div class="k">평균 점수</div><div class="v">${s.averageScore ?? '-'}</div></div>
                <div class="stat"><div class="k">전체 순위</div><div class="v">${s.allTimeRank != null ? s.allTimeRank + '위' : '-'}</div></div>`;
        } catch (e) {
            $('recordSummary').innerHTML = '';
            $('recordsMsg').textContent = e.message;
        }
    },

    async loadRecords(more) {
        try {
            const q = this.recordsCursor != null ? `?cursor=${this.recordsCursor}&size=20` : '?size=20';
            const r = await apiFetch(`/api/solo/records/me${q}`);
            const rows = (r.records ?? []).map((rec) => `
                <tr>
                    <td>${rec.score}</td>
                    <td>${rec.playTimeSeconds}초</td>
                    <td>${fmtDate(rec.createdAt)}</td>
                </tr>`).join('');
            if (more) $('recordsBody').insertAdjacentHTML('beforeend', rows);
            else $('recordsBody').innerHTML = rows || '<tr><td colspan="3">아직 기록이 없습니다.</td></tr>';
            this.recordsCursor = r.nextCursor;
            $('btnMoreRecords').classList.toggle('hidden', !r.hasNext);
        } catch (e) {
            if (!more) $('recordsMsg').textContent = e.message;
        }
    },

    async loadMatches(more) {
        try {
            const q = this.matchesCursor != null ? `?cursor=${this.matchesCursor}&size=20` : '?size=20';
            const r = await apiFetch(`/api/matches/me${q}`);
            if (r.summary) {
                const s = r.summary;
                $('matchSummary').textContent = `${s.totalMatches}전 ${s.wins}승 ${s.losses}패 ${s.draws}무`;
            }
            const resultLabel = {
                WIN: '승', LOSE: '패', DRAW: '무',
                FORFEIT_WIN: '몰수승', FORFEIT_LOSE: '몰수패',
            };
            const rows = (r.matches ?? []).map((m) => `
                <tr>
                    <td class="${m.result?.includes('WIN') ? 'win' : m.result === 'DRAW' ? '' : 'lose'}">${resultLabel[m.result] ?? m.result}</td>
                    <td>${m.myScore}</td>
                    <td>${escapeHtml(m.opponentNickname)}</td>
                    <td>${m.opponentScore}</td>
                    <td>${fmtDate(m.finishedAt)}</td>
                </tr>`).join('');
            if (more) $('matchesBody').insertAdjacentHTML('beforeend', rows);
            else $('matchesBody').innerHTML = rows || '<tr><td colspan="5">아직 대전 기록이 없습니다.</td></tr>';
            this.matchesCursor = r.nextCursor;
            $('btnMoreMatches').classList.toggle('hidden', !r.hasNext);
        } catch (e) {
            if (!more) $('recordsMsg').textContent = e.message;
        }
    },
};

/* ---------------- 내 정보 탭 ---------------- */
const Profile = {
    bind() {
        $('nicknameForm').addEventListener('submit', async (e) => {
            e.preventDefault();
            $('profileMsg').textContent = '';
            try {
                await apiFetch('/api/users/me/nickname', {
                    method: 'PATCH',
                    body: { nickname: $('newNickname').value.trim() },
                });
                $('newNickname').value = '';
                $('profileMsg').textContent = '닉네임이 변경되었습니다.';
                await this.load();
            } catch (err) {
                $('profileMsg').textContent = err.message;
            }
        });
    },

    async load() {
        try {
            const me = await apiFetch('/api/users/me');
            Auth.saveUser({ ...Auth.user, ...me });
            $('headerNickname').textContent = me.nickname;
            $('profileInfo').innerHTML = `
                <dt>이메일</dt><dd>${escapeHtml(me.email ?? '-')}</dd>
                <dt>닉네임</dt><dd>${escapeHtml(me.nickname)}</dd>
                <dt>가입 방식</dt><dd>${escapeHtml(me.provider ?? '-')}</dd>`;
        } catch (e) {
            $('profileInfo').innerHTML = '';
            $('profileMsg').textContent = e.message;
        }
    },
};

/* ---------------- 초기화 ---------------- */
document.querySelectorAll('.tab').forEach((b) => {
    b.addEventListener('click', () => showTab(b.dataset.tab));
});

window.addEventListener('auth:expired', () => {
    Battle.cleanup();
    showView('auth');
    authError('로그인이 만료되었습니다. 다시 로그인해주세요.');
});

Solo.init();
Battle.init();
bindAuth();
Ranking.bind();
Records.bind();
Profile.bind();

if (Auth.isLoggedIn) enterHome();
else showView('auth');
