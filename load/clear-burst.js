// Step 15 — 동시 clear 폭주 (WebSocket/STOMP)
//
// ROOMS개의 방에서 두 플레이어가 GAME_START를 받자마자 "같은 사각형 목록"을 같은 순서로 동시에 발사한다.
// Step 10의 AppleClearConcurrencyTest가 스레드 2개로 증명한 것을, 실제 소켓·STOMP 채널·Lua 경로 전체를 타서
// 방 수십 개 동시 부하에서도 성립하는지 본다.
//
// 정합성 불변식 (각 VU가 방 브로드캐스트를 전부 받으므로 클라이언트 쪽에서 검증 가능):
//   같은 칸(r:c)이 APPLES_CLEARED에 두 번 등장하면 안 된다  →  cells_double_cleared == 0
//
// 실행:
//   k6 run -e ROOMS=20 load/clear-burst.js
//   k6 run -e ROOMS=20 -e MAX_CLEARS=200 load/clear-burst.js
//
// 서버 쪽 관찰: clientInboundChannel 스레드 풀(기본 코어×2)에서 Lua 실행이 직렬화되는 지점,
//   Redis 커맨드 지연(redis-cli --latency), CLEAR_REJECTED 사유 분포.
//
// 전제: 서버는 SockJS 엔드포인트(/ws)를 열지만 Spring SockJS는 /ws/websocket 으로 raw WebSocket 접속을 허용한다.
//   k6 ws 모듈은 SockJS를 모르므로 이 raw 경로에 STOMP 프레임을 직접 쓴다.
import http from 'k6/http';
import ws from 'k6/ws';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const WS_URL = (__ENV.WS || BASE.replace(/^http/, 'ws')) + '/ws/websocket';
const ROOMS = parseInt(__ENV.ROOMS || '10', 10);
const MAX_CLEARS = parseInt(__ENV.MAX_CLEARS || '150', 10);
const SETTLE_MS = parseInt(__ENV.SETTLE_MS || '4000', 10); // 발사 후 응답이 모두 돌아올 때까지 기다리는 시간
const RUN_ID = Date.now().toString(36);

const gameStarted = new Counter('game_started');
const gameStartMissing = new Counter('game_start_missing');
const clearSent = new Counter('clear_sent');
const clearAccepted = new Counter('clear_accepted');
const rejectedTaken = new Counter('clear_rejected_already_taken');
const rejectedSum = new Counter('clear_rejected_invalid_sum');
const rejectedRange = new Counter('clear_rejected_invalid_range');
const rejectedOther = new Counter('clear_rejected_other');
const doubleCleared = new Counter('cells_double_cleared');
const firstResponseMs = new Trend('clear_first_response_ms', true);
const burstDrainMs = new Trend('clear_burst_drain_ms', true); // 첫 발사 → 마지막 응답

export const options = {
    scenarios: {
        pairs: {
            executor: 'per-vu-iterations',
            vus: ROOMS * 2,
            iterations: 1,
            maxDuration: '3m',
        },
    },
    thresholds: {
        cells_double_cleared: ['count==0'],  // 핵심 불변식
        game_start_missing: ['count==0'],    // 모든 방이 실제로 시작했어야 결과가 의미 있다
        clear_rejected_other: ['count==0'],  // 예상 밖 사유(NOT_PLAYING 등)는 시나리오 결함
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// ---------- STOMP 프레임 유틸 ----------
function frame(command, headers, body) {
    let s = command + '\n';
    for (const k in headers) s += `${k}:${headers[k]}\n`;
    if (body !== undefined) s += `content-length:${body.length}\n`;
    return s + '\n' + (body || '') + '\0';
}

// 한 WS 메시지에 프레임이 여러 개 올 수도 있고, 하트비트("\n")가 올 수도 있다.
function parseFrames(raw) {
    const out = [];
    for (const chunk of raw.split('\0')) {
        const text = chunk.replace(/^\n+/, '');
        if (!text) continue;
        const sep = text.indexOf('\n\n');
        const head = (sep === -1 ? text : text.slice(0, sep)).split('\n');
        const command = head.shift();
        const headers = {};
        for (const h of head) {
            const i = h.indexOf(':');
            if (i > 0) headers[h.slice(0, i)] = h.slice(i + 1);
        }
        out.push({ command, headers, body: sep === -1 ? '' : text.slice(sep + 2) });
    }
    return out;
}

// ---------- 합 10 사각형 열거 (프리픽스 합) ----------
function rectanglesSummingTo10(board) {
    const R = board.length, C = board[0].length;
    const P = Array.from({ length: R + 1 }, () => new Array(C + 1).fill(0));
    for (let r = 0; r < R; r++)
        for (let c = 0; c < C; c++)
            P[r + 1][c + 1] = board[r][c] + P[r][c + 1] + P[r + 1][c] - P[r][c];
    const rects = [];
    for (let r1 = 0; r1 < R; r1++)
        for (let r2 = r1; r2 < R; r2++)
            for (let c1 = 0; c1 < C; c1++)
                for (let c2 = c1; c2 < C; c2++) {
                    const sum = P[r2 + 1][c2 + 1] - P[r1][c2 + 1] - P[r2 + 1][c1] + P[r1][c1];
                    if (sum === 10) rects.push({ r1, c1, r2, c2 });
                    if (sum >= 10) break; // 값이 전부 양수라 c2를 늘리면 합이 커지기만 한다
                }
    return rects;
}

// ---------- setup: 유저·방 준비 (REST) ----------
function auth(email, nickname) {
    const headers = { 'Content-Type': 'application/json' };
    const signup = http.post(`${BASE}/api/auth/signup`,
        JSON.stringify({ email, password: 'LoadTest1!', nickname }), { headers });
    if (signup.status !== 201 && signup.status !== 409) fail(`signup ${email} → ${signup.status} ${signup.body}`);
    const login = http.post(`${BASE}/api/auth/login`,
        JSON.stringify({ email, password: 'LoadTest1!' }), { headers });
    if (login.status !== 200) fail(`login ${email} → ${login.status} ${login.body}`);
    return { token: login.json('result.accessToken'), userId: login.json('result.user.userId') };
}

export function setup() {
    const rooms = [];
    for (let i = 0; i < ROOMS; i++) {
        // 유저는 재실행에도 재사용(409 허용). 방은 매번 새로 만든다.
        const host = auth(`lt-cb-h${i}@load.test`, `ltcbh${i}`);
        const guest = auth(`lt-cb-g${i}@load.test`, `ltcbg${i}`);
        const create = http.post(`${BASE}/api/rooms`, null,
            { headers: { Authorization: `Bearer ${host.token}` } });
        if (create.status !== 201) fail(`room create → ${create.status} ${create.body}`);
        const code = create.json('result.roomCode');
        const join = http.post(`${BASE}/api/rooms/${code}/join`, null,
            { headers: { Authorization: `Bearer ${guest.token}` } });
        if (join.status !== 200) fail(`room join ${code} → ${join.status} ${join.body}`);
        rooms.push({ code, host, guest });
    }
    return { rooms };
}

// ---------- 본 시나리오: VU 한 명 = 플레이어 한 명 ----------
export default function (data) {
    const room = data.rooms[Math.floor((__VU - 1) / 2)];
    const me = (__VU - 1) % 2 === 0 ? room.host : room.guest;
    const topic = `/topic/room/${room.code}`;

    let started = false;
    let firedAt = 0;
    let lastResponseAt = 0;
    let firstResponseSeen = false;
    const seenCells = {};

    const res = ws.connect(WS_URL, {}, (socket) => {
        socket.on('open', () => {
            socket.send(frame('CONNECT', {
                'accept-version': '1.2',
                'heart-beat': '0,0',
                Authorization: `Bearer ${me.token}`,
            }));
        });

        socket.on('message', (raw) => {
            for (const f of parseFrames(raw)) {
                if (f.command === 'CONNECTED') {
                    socket.send(frame('SUBSCRIBE', { id: 'sub-room', destination: topic }));
                    socket.send(frame('SUBSCRIBE', { id: 'sub-err', destination: '/user/queue/errors' }));
                    socket.send(frame('SEND', { destination: `/app/room/${room.code}/ready` }));
                    continue;
                }
                if (f.command === 'ERROR') {
                    console.error(`STOMP ERROR (vu ${__VU}): ${f.headers.message} ${f.body}`);
                    socket.close();
                    continue;
                }
                if (f.command !== 'MESSAGE' || !f.body) continue;

                const msg = JSON.parse(f.body);
                if (msg.type === 'GAME_START' && !started) {
                    started = true;
                    gameStarted.add(1);
                    // 두 플레이어가 동일한 목록을 동일한 순서로 — 경합을 최대로
                    const rects = rectanglesSummingTo10(msg.board).slice(0, MAX_CLEARS);
                    firedAt = Date.now();
                    rects.forEach((rc, i) => {
                        socket.send(frame('SEND', {
                            destination: `/app/room/${room.code}/clear`,
                            'content-type': 'application/json',
                        }, JSON.stringify({ requestId: `${RUN_ID}-${__VU}-${i}`, ...rc })));
                    });
                    clearSent.add(rects.length);
                    socket.setTimeout(() => socket.close(), SETTLE_MS);
                } else if (msg.type === 'APPLES_CLEARED') {
                    noteResponse();
                    if (String(msg.clearedBy) === String(me.userId)) clearAccepted.add(1);
                    for (const cell of msg.cells) {
                        const key = `${cell.r}:${cell.c}`;
                        if (seenCells[key]) {
                            doubleCleared.add(1);
                            console.error(`DOUBLE CLEAR room=${room.code} cell=${key}`);
                        }
                        seenCells[key] = true;
                    }
                } else if (msg.type === 'CLEAR_REJECTED') {
                    noteResponse();
                    if (msg.reason === 'ALREADY_TAKEN') rejectedTaken.add(1);
                    else if (msg.reason === 'INVALID_SUM') rejectedSum.add(1);
                    else if (msg.reason === 'INVALID_RANGE') rejectedRange.add(1);
                    else { rejectedOther.add(1); console.error(`unexpected reason ${msg.reason}`); }
                }
            }
        });

        socket.on('error', (e) => console.error(`ws error (vu ${__VU}): ${e.error()}`));

        // 상대가 끝내 안 오면(방 준비 실패 등) 무한 대기하지 않는다
        socket.setTimeout(() => {
            if (!started) { gameStartMissing.add(1); socket.close(); }
        }, 15000);

        function noteResponse() {
            const now = Date.now();
            lastResponseAt = now;
            if (!firstResponseSeen) { firstResponseSeen = true; firstResponseMs.add(now - firedAt); }
        }
    });

    check(res, { 'ws 101': (r) => r && r.status === 101 });
    if (started && lastResponseAt) burstDrainMs.add(lastResponseAt - firedAt);
}
