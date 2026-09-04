// Step 15 — 동시 회원가입
//
//   MODE=unique : VU마다 다른 이메일로 가입 — BCrypt 해싱(CPU)이 처리량을 어디까지 제한하는지 본다
//   MODE=dup    : 100 VU가 "같은 이메일"로 동시에 가입 — 선(先) 조회 중복 검사의 틈(check-then-act)을 뚫고
//                 UNIQUE 제약까지 도달하는 요청이 얼마나 되는지, 그때 서버가 409를 주는지 500을 주는지 본다
//                 (Step 2 학습 포인트: "제약이 최후의 방어선" — 방어선에 걸렸을 때의 응답도 설계의 일부)
//
// 실행:
//   k6 run -e MODE=unique load/signup-burst.js
//   k6 run -e MODE=dup    load/signup-burst.js
//
// 정리: 가입된 더미는 이메일이 lt-*@load.test 이므로
//   DELETE FROM users WHERE email LIKE 'lt-%@load.test';
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'unique';

const created = new Counter('signup_201');
const conflict = new Counter('signup_409');
const serverError = new Counter('signup_5xx');

export const options = {
    scenarios: MODE === 'dup'
        ? {
            same_email: {
                executor: 'per-vu-iterations',
                vus: 100,
                iterations: 1,
                maxDuration: '30s',
            },
        }
        : {
            ramp: {
                executor: 'ramping-vus',
                startVUs: 0,
                stages: [
                    { duration: '10s', target: 20 },
                    { duration: '30s', target: 50 },
                    { duration: '10s', target: 0 },
                ],
            },
        },
    thresholds: {
        // 5xx는 어떤 모드에서도 0이어야 한다 — dup 모드에서 500이 나오면 UNIQUE 제약 위반이 그대로 새는 것
        signup_5xx: ['count==0'],
        http_req_duration: ['p(95)<1000'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// 실행 ID는 setup()에서 한 번만 만든다 — init 컨텍스트는 VU마다 따로 돌아서 거기서 만들면 VU마다 값이 달라진다.
// (dup 모드에서 "같은 이메일"이 되려면 모든 VU가 같은 값을 써야 한다)
export function setup() {
    return { runId: Date.now().toString(36).slice(-4) };
}

export default function (data) {
    // unique: 닉네임은 12자 제한 — 4(run) + VU(≤3) + 'x' + ITER(≤4) = 12자 이내로 유지
    const suffix = MODE === 'dup' ? data.runId : `${data.runId}${__VU}x${__ITER}`;
    const body = JSON.stringify({
        email: `lt-${suffix}@load.test`,
        password: 'LoadTest1!',
        nickname: `lt${suffix}`,
    });
    const res = http.post(`${BASE}/api/auth/signup`, body, {
        headers: { 'Content-Type': 'application/json' },
    });

    if (res.status === 201) created.add(1);
    else if (res.status === 409) conflict.add(1);
    else if (res.status >= 500) serverError.add(1);

    check(res, {
        'no 5xx': (r) => r.status < 500,
        'unique → 201': (r) => MODE === 'dup' || r.status === 201,
    });
}
