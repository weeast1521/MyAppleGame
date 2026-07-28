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
        $('btnSoloFinish').addEventListener('click', () => finish('기록 제출'));
        $('btnSoloAgain').addEventListener('click', () => showPanel('intro'));
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
            $('soloMsg').textContent = e.message;
        }
    }

    function startTimer(limit) {
        clearInterval(timerId);
        let remaining = limit;
        renderTimer($('soloTimer'), remaining);
        timerId = setInterval(() => {
            remaining -= 1;
            renderTimer($('soloTimer'), remaining);
            if (remaining <= 0) {
                clearInterval(timerId);
                finish('시간 종료');
            }
        }, 1000);
    }

    function handleSelect({ r1, c1, r2, c2, sum, cells }) {
        if (sum !== 10) return;
        board.removeCells(cells);
        moves.push({ r1, c1, r2, c2, elapsedMs: Date.now() - startedAt });
        score += cells.length;
        $('soloScore').textContent = score;
        if (board.isEmpty()) finish('보드 클리어 🎉');
    }

    async function finish(reasonText) {
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
            $('soloStatus').textContent = `기록 제출 실패: ${e.message} — 버튼으로 다시 제출할 수 있습니다.`;
            submitting = false;
        }
    }

    function renderResult(r) {
        $('soloResultGrid').innerHTML = `
            <div class="stat"><div class="k">점수</div><div class="v">${r.score}</div></div>
            <div class="stat"><div class="k">최대 콤보</div><div class="v">${r.maxCombo}</div></div>
            <div class="stat"><div class="k">개인 최고</div><div class="v">${r.isPersonalBest ? '🏆 갱신!' : '-'}</div></div>
            <div class="stat"><div class="k">전체 순위</div><div class="v">${r.allTimeRank != null ? r.allTimeRank + '위' : '-'}</div></div>`;
    }

    return { init };
})();
