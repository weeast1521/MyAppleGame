'use strict';

/* ================================================================
 * 솔로 모드
 *  1) POST /api/solo/games              → 보드 발급, 게임 시작
 *  2) 드래그로 합 10 조합 제거 (클라이언트 선반영 + moves 기록)
 *  3) POST /api/solo/games/{id}/finish  → 서버가 moves 재검증 후 기록 저장
 * ================================================================ */
const Solo = (() => {
    let board = null;
    let session = null;   // { gameSessionId, boardSeed, board, timeLimitSeconds }
    let moves = [];
    let score = 0;
    let startedAt = 0;
    let timeLimit = 120;
    let timerId = null;
    let submitting = false;

    function init() {
        board = createBoard({
            wrapEl: $('soloBoardWrap'),
            boardEl: $('soloBoard'),
            selBoxEl: $('soloSelBox'),
            onSelect: handleSelect,
        });
        $('btnSoloStart').addEventListener('click', start);
        $('btnSoloAgain').addEventListener('click', start);

        // 브라우저는 비활성 탭의 setInterval을 조이고(크롬은 1분 1회 수준), 화면이 잠기거나
        // 뒤로가기로 페이지가 bfcache에 얼리면 아예 멈춘다. 그동안 서버 세션 TTL은 실시간으로
        // 흐르므로 복귀가 늦으면 제출이 거부됐다(#49). 복귀하는 순간 한 번 직접 판정해
        // 다음 인터벌 틱(최대 1분)을 기다리지 않는다.
        document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(); });
        // bfcache에서 복원된 경우 — persisted가 true면 JS 상태와 타이머가 얼어 있다가 살아난 것이다
        window.addEventListener('pageshow', (e) => { if (e.persisted) tick(); });
    }

    function showPanel(name) {
        $('soloIntro').classList.toggle('hidden', name !== 'intro');
        $('soloGame').classList.toggle('hidden', name !== 'game');
        $('soloResult').classList.toggle('hidden', name !== 'result');
    }

    async function start() {
        $('soloMsg').textContent = '';
        try {
            session = await apiFetch('/api/solo/games', { method: 'POST' });
            moves = [];
            score = 0;
            submitting = false;
            $('soloScore').textContent = '0';
            $('soloStatus').textContent = '합이 10이 되도록 드래그하세요!';
            showPanel('game');
            board.setBoard(session.board);
            board.setActive(true);
            startedAt = Date.now();
            timeLimit = session.timeLimitSeconds ?? 120;
            startTimer();
        } catch (e) {
            // soloMsg는 인트로 패널에만 있다. 결과 화면에서 '다시 하기'로 들어와 실패한 경우
            // 인트로로 되돌려야 오류가 보이고, '게임 시작'으로 재시도할 수 있다.
            showPanel('intro');
            $('soloMsg').textContent = e.message;
        }
    }

    function startTimer() {
        clearInterval(timerId);
        renderTimer($('soloTimer'), timeLimit);
        timerId = setInterval(tick, 250);   // 게임 길이와 무관해졌으므로 간격을 좁혀 복귀 시 즉시 판정
    }

    // 남은 시간 판정 — 인터벌뿐 아니라 탭 복귀·bfcache 복원 시에도 직접 호출된다.
    // 틱을 세면 백그라운드 탭·절전에서 호출이 밀린 만큼 오차가 누적된다.
    // startedAt 기준으로 매번 다시 계산해야 서버 세션 TTL과 시간축이 어긋나지 않는다.
    function tick() {
        if (!session || submitting) return;
        const remaining = timeLimit - Math.floor((Date.now() - startedAt) / 1000);
        renderTimer($('soloTimer'), remaining);
        if (remaining <= 0) {
            clearInterval(timerId);
            finish('시간 종료');
        }
    }

    function handleSelect({ r1, c1, r2, c2, sum, cells }) {
        if (sum !== 10) return;
        board.removeCells(cells);
        moves.push({ r1, c1, r2, c2, elapsedMs: Date.now() - startedAt });
        score += cells.length;
        $('soloScore').textContent = score;
        if (board.isEmpty()) {
            $('soloStatus').textContent = '보드 클리어 🎉 — 제한시간이 끝나면 기록이 자동 제출됩니다.';
        }
    }

    async function finish(reasonText, attempt = 1) {
        if (!session || submitting) return;
        submitting = true;
        board.setActive(false);
        clearInterval(timerId);
        $('soloStatus').textContent = `${reasonText} — 기록 제출 중…`;
        try {
            const r = await apiFetch(`/api/solo/games/${session.gameSessionId}/finish`, {
                method: 'POST',
                body: { moves },
            });
            renderResult(r);
            showPanel('result');
            session = null;
        } catch (e) {
            // 세션 없음(404)·중복 제출(409)은 재시도해도 결과가 같다
            const retryable = e.code !== 'SOLO404' && e.code !== 'SOLO409';
            if (retryable && attempt < 3) {
                $('soloStatus').textContent = `제출 실패 — 재시도 중 (${attempt}/3)…`;
                submitting = false;
                setTimeout(() => finish(reasonText, attempt + 1), 1000 * attempt);
                return;
            }
            // 실패 사유를 구분해 알린다 — "기록이 없다"와 "저장에 실패했다"가 같아 보이면
            // 사용자는 무엇을 다시 해야 할지 알 수 없다.
            // 제한시간을 벗어난 기록(SOLO400_1)은 제출이 아주 오래 밀린 경우다.
            const reason = {
                SOLO404: '게임 세션이 만료되어 기록을 저장하지 못했습니다. 게임 중 페이지를 벗어나거나 오래 자리를 비우면 발생합니다.',
                SOLO409: '이미 제출된 게임입니다.',
                SOLO400_1: '제한시간을 벗어난 기록이라 저장하지 못했습니다.',
            }[e.code] ?? `기록 제출 실패: ${e.message}`;

            // 게임 화면에 갇히지 않도록 인트로로 돌려보낸다 — 거기서 바로 새로 시작할 수 있다
            session = null;
            submitting = false;
            showPanel('intro');
            $('soloMsg').textContent = reason;
        }
    }

    function renderResult(r) {
        $('soloResultGrid').innerHTML = `
            <div class="stat"><div class="k">점수</div><div class="v">${r.score}</div></div>
            <div class="stat"><div class="k">개인 최고</div><div class="v">${r.isPersonalBest ? '🏆 갱신!' : '-'}</div></div>
            <div class="stat"><div class="k">전체 순위</div><div class="v">${r.allTimeRank != null ? r.allTimeRank + '위' : '-'}</div></div>`;
    }

    return { init };
})();
