// Step 14 — 인증 필터 "요청당 SELECT 1번"의 부하 비용 측정
// 같은 엔드포인트를 비로그인/로그인으로 때려 차이를 본다. 로그인 쪽은 요청마다 users SELECT 1번이 추가된다.
//
// 실행 (로컬 앱 8080 기동 후):
//   TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
//          -d '{"email":"...","password":"..."}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["result"]["accessToken"])')
//   k6 run -e MODE=anon load/auth-cost.js
//   k6 run -e MODE=auth -e TOKEN=$TOKEN load/auth-cost.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'anon';
const TOKEN = __ENV.TOKEN || '';

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 50 },
                { duration: '30s', target: 200 },
                { duration: '10s', target: 0 },
            ],
        },
    },
    thresholds: { http_req_failed: ['rate<0.01'] },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const params = MODE === 'auth' ? { headers: { Authorization: `Bearer ${TOKEN}` } } : {};
    const res = http.get(`${BASE}/api/rankings/solo?period=alltime&size=20`, params);
    check(res, { 'status 200': (r) => r.status === 200 });
}
