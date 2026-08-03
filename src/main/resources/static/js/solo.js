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
            startTimer(session.timeLimitSeconds ?? 120);
        } catch (e) {
            // soloMsg는 인트로 패널에만 있다. 결과 화면에서 '다시 하기'로 들어와 실패한 경우
            // 인트로로 되돌려야 오류가 보이고, '게임 시작'으로 재시도할 수 있다.
            showPanel('intro');
            $('soloMsg').textContent = e.message;
        }
    }

    function startTimer(limit) {
        clearInterval(timerId);
        renderTimer($('soloTimer'), limit);
        timerId = setInterval(() => {
            // 틱을 세면 백그라운드 탭·절전에서 호출이 밀린 만큼 오차가 누적된다.
            // startedAt 기준으로 매번 다시 계산해야 서버 세션 TTL과 시간축이 어긋나지 않는다.
            const remaining = limit - Math.floor((Date.now() - startedAt) / 1000);
            renderTimer($('soloTimer'), remaining);
            if (remaining <= 0) {
                clearInterval(timerId);
                finish('시간 종료');
            }
        }, 250);   // 게임 길이와 무관해졌으므로 간격을 좁혀 복귀 시 즉시 판정
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
            $('soloStatus').textContent = `기록 제출 실패: ${e.message}`;
            submitting = false;
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
