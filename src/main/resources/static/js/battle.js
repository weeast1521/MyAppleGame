'use strict';

/* ================================================================
 * 2인 대전 모드 (연전 지원)
 *  - REST : 방 생성(POST /api/rooms), 입장(POST /api/rooms/{code}/join),
 *           나가기(DELETE /api/rooms/{code}/leave)
 *  - WS   : SockJS + STOMP (/ws), CONNECT 헤더에 JWT
 *           구독: /topic/room/{roomCode}, /user/queue/errors
 *           발행: /app/room/{roomCode}/ready, /app/room/{roomCode}/clear
 *  - 브로드캐스트는 type 필드로 구분:
 *           GAME_START / APPLES_CLEARED / GAME_END / OPPONENT_STATUS / PLAYER_LEFT
 *  - 같은 방에서 판이 끝나면 다시 ready → 다음 판. 누적 점수(totalScores)는
 *    방 단위로 유지되고, 한 명이 나가면(PLAYER_LEFT) 초기화된다.
 * ================================================================ */
const Battle = (() => {
    let board = null;
    let stomp = null;
    let roomCode = null;
    let myId = null;      // 문자열 userId (scores 의 key 와 비교)
    let oppId = null;
    let players = {};     // userId → nickname
    let scores = {};      // userId → 이번 판 점수
    let totals = {};      // userId → 누적 점수 (방 단위, 이탈 시 초기화)
    let round = 0;        // 현재 판 번호
    let playing = false;
    let timerId = null;
    let flashId = null;

    function init() {
        board = createBoard({
            wrapEl: $('battleBoardWrap'),
            boardEl: $('battleBoard'),
            selBoxEl: $('battleSelBox'),
            onSelect: handleSelect,
        });
        $('btnRoomCreate').addEventListener('click', createRoom);
        $('btnRoomJoin').addEventListener('click', joinRoom);
        $('btnRoomLeave').addEventListener('click', leaveRoom);
        $('btnRematch').addEventListener('click', requestRematch);
        $('btnCopyCode').addEventListener('click', () => {
            navigator.clipboard.writeText(roomCode);
            $('btnCopyCode').textContent = '복사됨';
            setTimeout(() => ($('btnCopyCode').textContent = '복사'), 1200);
        });
    }

    function showPanel(name) {
        $('battleLobby').classList.toggle('hidden', name !== 'lobby');
        $('battleRoom').classList.toggle('hidden', name !== 'room');
    }

    /* ---------------- REST: 방 생성/입장/나가기 ---------------- */
    async function createRoom() {
        $('battleMsg').textContent = '';
        try {
            const r = await apiFetch('/api/rooms', { method: 'POST' });
            enterRoom(r.roomCode);
            $('battleStatus').textContent = '상대 플레이어를 기다리는 중… 방 코드를 공유하세요.';
        } catch (e) {
            $('battleMsg').textContent = e.message;
        }
    }

    async function joinRoom() {
        const code = $('joinRoomCode').value.trim();
        if (!code) return ($('battleMsg').textContent = '방 코드를 입력하세요.');
        $('battleMsg').textContent = '';
        try {
            const r = await apiFetch(`/api/rooms/${code}/join`, { method: 'POST' });
            enterRoom(r.roomCode);
            if (r.host) setOpponent(String(r.host.userId), r.host.nickname);
            $('battleStatus').textContent = '입장 완료 — 게임이 곧 시작됩니다…';
        } catch (e) {
            $('battleMsg').textContent = e.message;
        }
    }

    async function leaveRoom() {
        try {
            await apiFetch(`/api/rooms/${roomCode}/leave`, { method: 'DELETE' });
        } catch { /* 이미 정리된 방 — 무시 */ }
        cleanup();
        showPanel('lobby');
    }

    function enterRoom(code) {
        roomCode = code;
        myId = String(Auth.user?.userId ?? '');
        players = {};
        scores = {};
        oppId = null;
        resetSession();
        playing = false;
        clearInterval(timerId);
        board.clear();
        $('roomCode').textContent = code;
        $('meName').textContent = (Auth.user?.nickname ?? '나') + ' (나)';
        $('meScore').textContent = '0';
        $('oppScore').textContent = '0';
        $('oppName').textContent = '상대 대기 중…';
        $('btnRematch').classList.add('hidden');
        renderTimer($('battleTimer'), null);
        showPanel('room');
        connect();
    }

    // 누적 점수/판 카운트 초기화 (입장 시 · 상대가 나갔을 때)
    function resetSession() {
        totals = {};
        round = 0;
        refreshTotals();
    }

    /* ---------------- WebSocket ---------------- */
    function connect() {
        disconnect();
        stomp = new StompJs.Client({
            webSocketFactory: () => new SockJS('/ws'),
            connectHeaders: { Authorization: `Bearer ${Auth.accessToken}` },
            reconnectDelay: 3000,
            onConnect: () => {
                stomp.subscribe(`/topic/room/${roomCode}`, (f) => handleBroadcast(JSON.parse(f.body)));
                stomp.subscribe('/user/queue/errors', (f) => handlePrivate(JSON.parse(f.body)));
                stomp.publish({ destination: `/app/room/${roomCode}/ready`, body: '{}' });
            },
            onStompError: (frame) => {
                $('battleStatus').textContent = 'WebSocket 오류: ' + (frame.headers['message'] ?? '연결 실패');
            },
        });
        stomp.activate();
    }

    function disconnect() {
        if (stomp) {
            stomp.deactivate();
            stomp = null;
        }
    }

    function cleanup() {
        disconnect();
        clearInterval(timerId);
        board.setActive(false);
        playing = false;
    }

    function handleBroadcast(msg) {
        switch (msg.type) {
            case 'GAME_START': return onGameStart(msg);
            case 'APPLES_CLEARED': return onApplesCleared(msg);
            case 'GAME_END': return onGameEnd(msg);
            case 'OPPONENT_STATUS': return onOpponentStatus(msg);
            case 'PLAYER_LEFT': return onPlayerLeft(msg);
        }
    }

    function handlePrivate(msg) {
        if (msg.type !== 'CLEAR_REJECTED') return;
        const text = {
            ALREADY_TAKEN: '한발 늦었어요! 상대가 먼저 가져갔습니다. ⚡',
            INVALID_SUM: '합이 10이 아닙니다.',
            INVALID_RANGE: '잘못된 선택 범위입니다.',
        }[msg.reason] ?? `제거 실패 (${msg.reason})`;
        flashStatus(text);
    }

    /* ---------------- 메시지 핸들러 ---------------- */
    function onGameStart(msg) {
        playing = true;
        round = msg.round ?? round + 1;
        if (msg.players) {
            players = msg.players;
            const opp = Object.keys(players).find((id) => id !== myId);
            if (opp) setOpponent(opp, players[opp]);
        }
        scores = {};
        for (const id of Object.keys(players)) scores[id] = 0;
        board.setBoard(msg.board);
        board.setActive(true);
        refreshScores();
        refreshTotals();
        $('btnRematch').classList.add('hidden');
        $('battleStatus').textContent = `${round}판 시작! 합이 10이 되게 드래그하세요.`;
        startTimer(msg.timeLimitSeconds ?? 120);
    }

    function onApplesCleared(msg) {
        board.removeCells(msg.cells);
        if (msg.scores) scores = msg.scores;
        if (!oppId) {
            const opp = Object.keys(scores).find((id) => id !== myId);
            if (opp) setOpponent(opp, players[opp]);
        }
        refreshScores();
    }

    function onGameEnd(msg) {
        playing = false;
        board.setActive(false);
        clearInterval(timerId);
        renderTimer($('battleTimer'), 0);
        if (msg.scores) {
            scores = msg.scores;
            refreshScores();
        }
        // 누적 점수: 서버가 주면 그 값을, 없으면 이번 판 점수를 로컬 합산 (백엔드 구현 전 임시)
        if (msg.totalScores) {
            totals = msg.totalScores;
        } else {
            for (const [id, s] of Object.entries(scores)) totals[id] = (totals[id] ?? 0) + s;
        }
        refreshTotals();

        const myResult = msg.results?.[myId];
        let text;
        if (myResult) {
            text = {
                WIN: '🎉 승리!', LOSE: '아쉽게 패배… 😢', DRAW: '무승부! 🤝',
                FORFEIT_WIN: '상대 이탈 — 몰수승! 🎉', FORFEIT_LOSE: '몰수패… 😢',
            }[myResult] ?? myResult;
        } else if (msg.winnerUserId == null) {
            text = '무승부! 🤝';
        } else {
            text = String(msg.winnerUserId) === myId ? '🎉 승리!' : '아쉽게 패배… 😢';
        }
        const reason = { TIME_UP: '시간 종료', OPPONENT_LEFT: '상대 이탈', ABORTED: '게임 중단' }[msg.reason] ?? msg.reason ?? '';
        $('battleStatus').textContent = `${round}판 종료 (${reason}) — ${text}`;

        // 상대가 남아 있으면 같은 방에서 다음 판 진행 가능
        if (msg.reason === 'TIME_UP') $('btnRematch').classList.remove('hidden');
    }

    function onOpponentStatus(msg) {
        if (String(msg.userId) === myId) return;
        if (msg.status === 'DISCONNECTED') {
            $('battleStatus').textContent = `상대 연결 끊김 — ${msg.graceSeconds ?? '?'}초 내 재접속을 기다립니다…`;
        } else if (msg.status === 'RECONNECTED') {
            $('battleStatus').textContent = '상대가 재접속했습니다. 게임 계속!';
        }
    }

    // 상대가 방을 나감 → 누적 점수 초기화, 새 상대 대기
    function onPlayerLeft(msg) {
        if (String(msg.userId) === myId) return;
        playing = false;
        board.setActive(false);
        board.clear();
        clearInterval(timerId);
        renderTimer($('battleTimer'), null);
        oppId = null;
        scores = {};
        resetSession();
        $('oppName').textContent = '상대 대기 중…';
        $('meScore').textContent = '0';
        $('oppScore').textContent = '0';
        $('btnRematch').classList.add('hidden');
        $('battleStatus').textContent = '상대가 방을 나갔습니다. 누적 점수가 초기화되었습니다 — 새 상대를 기다립니다.';
    }

    /* ---------------- 게임 입력/표시 ---------------- */
    function requestRematch() {
        if (!stomp) return;
        stomp.publish({ destination: `/app/room/${roomCode}/ready`, body: '{}' });
        $('btnRematch').classList.add('hidden');
        $('battleStatus').textContent = '다음 판 준비 완료 — 상대의 준비를 기다립니다…';
    }

    // 멱등 검사용 요청 ID (서버가 재전송을 걸러내는 키).
    // crypto.randomUUID()는 보안 컨텍스트(HTTPS·localhost) 전용이라 http+IP 배포에서는
    // 함수 자체가 없다(undefined) — getRandomValues(어디서나 제공, 같은 CSPRNG)로
    // 동일한 UUID v4를 직접 조립하는 폴백을 둔다. HTTPS(Phase 2) 후엔 위 분기를 탄다.
    function genRequestId() {
        if (crypto.randomUUID) return crypto.randomUUID();
        const b = crypto.getRandomValues(new Uint8Array(16));
        b[6] = (b[6] & 0x0f) | 0x40; // version 4
        b[8] = (b[8] & 0x3f) | 0x80; // variant (RFC 4122)
        const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
        return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
    }

    // 합이 10인 선택만 서버로 전송 — 판정(존재/합/경합)은 전적으로 서버가 한다.
    function handleSelect({ r1, c1, r2, c2, sum }) {
        if (!playing || sum !== 10 || !stomp) return;
        stomp.publish({
            destination: `/app/room/${roomCode}/clear`,
            body: JSON.stringify({ requestId: genRequestId(), r1, c1, r2, c2 }),
        });
    }

    function setOpponent(id, nickname) {
        oppId = id;
        $('oppName').textContent = nickname ?? '상대';
    }

    function refreshScores() {
        $('meScore').textContent = scores[myId] ?? 0;
        $('oppScore').textContent = oppId ? (scores[oppId] ?? 0) : 0;
    }

    // 상단 누적 점수판 — 판이 끝날 때마다 갱신, 이탈 시 초기화
    function refreshTotals() {
        $('roundInfo').textContent = round > 0 ? `${round}판` : '';
        $('meTotal').textContent = totals[myId] ?? 0;
        $('oppTotal').textContent = oppId ? (totals[oppId] ?? 0) : 0;
    }

    function startTimer(limit) {
        clearInterval(timerId);
        let remaining = limit;
        renderTimer($('battleTimer'), remaining);
        timerId = setInterval(() => {
            remaining -= 1;
            renderTimer($('battleTimer'), remaining);
            if (remaining <= 0) clearInterval(timerId); // 최종 종료 판정은 서버의 GAME_END
        }, 1000);
    }

    function flashStatus(text) {
        const el = $('battleStatus');
        el.textContent = text;
        clearTimeout(flashId);
        flashId = setTimeout(() => {
            if (playing) el.textContent = '합이 10이 되게 드래그하세요.';
        }, 1500);
    }

    return { init, cleanup };
})();
