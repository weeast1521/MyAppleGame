// Step 15 — 랭킹 조회 폭주
//
// 두 가지를 본다.
//   MODE=hit  : 캐시(Redis Sorted Set)가 따뜻한 상태에서 200 VU가 때릴 때의 처리량/지연 — 평상시 상한
//   MODE=miss : 캐시가 비어 있는 순간 200 VU가 동시에 들어올 때 — 캐시 스탬피드(cache stampede) 재현
//               warm-up(200만 건 집계)이 요청 수만큼 중복 실행되는지, 그동안 DB 커넥션 풀이 어떻게 되는지 관찰
//
// 실행 (로컬 앱 8080 기동 후):
//   k6 run -e MODE=hit load/ranking-burst.js
//   docker exec apple-redis redis-cli DEL ranking:solo:alltime ranking:solo:alltime:warmed   # 캐시 비우기
//   k6 run -e MODE=miss load/ranking-burst.js
//
// k6가 없으면 Docker로: docker run --rm -i -v "$PWD/load:/load" grafana/k6 run -e BASE=http://host.docker.internal:8080 -e MODE=hit /load/ranking-burst.js
//
// 서버 쪽 관찰 포인트:
//   - 앱 로그 `source=db` 줄 수 = warm-up이 실제로 실행된 횟수 (1이어야 정상, 스탬피드면 수십~수백)
//   - /actuator/prometheus 의 hikaricp_connections_pending / hikaricp_connections_acquire_seconds_max
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'hit';

const sourceDb = new Counter('ranking_source_db');
const sourceRedis = new Counter('ranking_source_redis');

// hit: 램프업으로 정상 부하 상한을 본다.
// miss: 램프업 없이 200 VU가 동시에 출발 — "캐시 만료 직후 트래픽"을 흉내 낸다.
export const options = {
    scenarios: MODE === 'miss'
        ? {
            stampede: {
                executor: 'per-vu-iterations',
                vus: 200,
                iterations: 5,
                maxDuration: '2m',
            },
        }
        : {
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
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: MODE === 'miss' ? ['p(95)<5000'] : ['p(95)<300'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const res = http.get(`${BASE}/api/rankings/solo?period=alltime&size=20`);
    const ok = check(res, { 'status 200': (r) => r.status === 200 });
    if (ok) {
        const source = res.json('result.source');
        if (source === 'db') sourceDb.add(1);
        else sourceRedis.add(1);
    }
}
