# Step 14 — DB 성능 개선 실습 기록

> 2026-09-04 · 로컬 Docker MySQL 8.4 · `solo_record` 2,019,026건 / 유저 10,000명 (`SoloRecordDummyGenerator`)
> 숫자는 이 맥의 로컬 환경 기준이라 **절대치가 아니라 before/after 상대 비교**가 목적이다.
> 재현: `DB_EXPERIMENT=true ./gradlew test --tests DbPerformanceExperimentTest` → `build/reports/db-experiment.md`
> 부하: `k6 run -e MODE=anon|auth -e TOKEN=… load/auth-cost.js` (로컬 앱 8080 기동 후)

## 0. 한 장 요약

| 실험 | 전 | 후 | 배수 | 적용 |
|---|---|---|---|---|
| E1 offset 1,500,000 vs 커서 | 285 ms | 0.6 ms | ~475× | 이미 커서(Step 6) |
| E2 전체 랭킹 캐시 미스 (서비스) | 1,829 ms | **80 ms** | 23× | V3 인덱스 + 네이티브 쿼리 |
| E2 전체 랭킹 SQL만 | 1,662 ms | 29 ms | 57× | 〃 |
| E2 주간 랭킹 SQL | 1,415 ms | 43 ms | 33× | 〃 |
| E2 캐시 히트(Redis) | 7.2 ms | — | DB 대비 254× | 이미(Step 7) |
| E3 대전 전적 20건 쿼리 수 | 42 | 3 | 14× | 이미 fetch join(#28) |
| E4 인증 요청 SQL 수 | 2 | 1 | — | **role 클레임** |
| E4 k6 200 VU 처리량(로그인) | 840 req/s | **1,265 req/s** | +51% | 〃 |
| E4 k6 200 VU p95(로그인) | 231 ms | **154 ms** | −33% | 〃 |
| E5 1만 건 INSERT | 3,486 ms (JPA) | 110 ms (JDBC batch) | 32× | 이미(더미 생성기) |

---

## E1. offset vs 커서 페이지네이션

**가설** — offset은 건너뛸 행을 전부 읽고 버리므로 깊어질수록 선형으로 느려진다. 커서(`WHERE id < ?`)는 어디서든 인덱스 범위 시작점으로 점프하므로 일정하다.

| offset | offset 방식 | 같은 위치 커서 |
|---|---|---|
| 0 | 0.64 ms | 0.55 ms |
| 10,000 | 1.87 ms | 0.52 ms |
| 100,000 | 15.05 ms | 0.48 ms |
| 500,000 | 70.08 ms | 0.69 ms |
| 1,000,000 | 139.86 ms | 0.87 ms |
| 1,500,000 | 285.44 ms | 0.60 ms |

실행계획(offset 1,000,000):
```
-> Limit/Offset: 20/1000000 row(s)  (actual time=225..225 rows=20)
    -> Index scan on solo_record using PRIMARY (reverse)  (actual rows=1e+6)   ← 100만 행을 읽고 버린다
```
같은 위치 커서:
```
-> Limit: 20 row(s)  (actual time=0.0242..0.0276 rows=20)
    -> Index range scan on solo_record using PRIMARY over (id < 1019027) (reverse)  (actual rows=20)   ← 20행만
```

**읽는 법** — `rows=1e+6`이 핵심. LIMIT 20인데 스토리지 엔진이 100만 행을 반환했다. offset은 "위치"가 아니라 "몇 개 버릴지"이므로 DB는 버릴 행도 읽어야 한다. 커서는 `id < ?`가 인덱스 조건이 되어 B-tree에서 시작점을 바로 찾는다.

**유저 단위(기록 400건)** — offset 375: 0.76 ms / 커서 0.50 ms. 수백 건 규모에선 차이가 작다. 커서의 가치는 "전체 기록" 같은 큰 집합에서 드러난다. 내 기록·대전 전적이 커서인 이유.

**트레이드오프** — 커서는 "n페이지로 점프"가 불가능하다(이전/다음만). 랭킹처럼 "상위 100"만 보여주는 화면은 offset 상한(MAX_RANGE=100)을 두면 안전하다.

---

## E2. 랭킹 — DB 집계 vs Redis, 인덱스, 쿼리 재작성

### 2-1. 출발점: 전체 랭킹 JPQL은 1.6초

서비스가 쓰던 JPQL(`FROM SoloRecord r JOIN r.user u GROUP BY u.id, u.nickname`)의 실행계획:
```
-> Sort: best DESC  (actual time=1851..1851 rows=10000)
    -> Aggregate using temporary table  (actual time=1848..1848 rows=10000)
        -> Nested loop inner join  (actual time=0.54..1388 rows=2.02e+6)      ← 200만 행의 조인 결과
            -> Covering index scan on u using uk_users_nickname  (rows=10002)  ← users가 바깥(driving)
            -> Index lookup on r using idx_solo_record_user_id (user_id=u.id)  (rows=202 loops=10002)
```
**읽는 법** — 옵티마이저가 users를 바깥 테이블로 골랐다(작으니까). 유저마다 solo_record 202건을 인덱스로 찾아 **200만 행의 조인 결과를 만든 뒤** 임시 테이블에서 집계한다. 집계 전에 조인하는 것이 낭비의 원인이다.

### 2-2. 서비스 경로: 캐시 미스 1,829 ms vs 캐시 히트 7.2 ms (254×)

Step 7의 Redis ZSet 캐시가 하는 일이 이것이다. 하지만 캐시 미스(재시작·주간 키 전환·#12 같은 오염 복구)는 여전히 1.8초 — 그 순간의 요청은 느리다. 아래는 캐시 미스 자체를 줄이는 작업.

### 2-3. 인덱스 `(user_id, score)` — Loose Index Scan

**가설** — `GROUP BY user_id, MAX(score)`는 user_id별로 score의 최댓값 하나만 필요하다. `(user_id, score)` 인덱스가 있으면 각 user_id 그룹의 **끝 항목만** 읽으면 된다(Loose Index Scan / MySQL 8: "skip scan for grouping"). 200만 행 스캔 → 1만 번의 점프.

| | 집계만 (`GROUP BY user_id` LIMIT 100) |
|---|---|
| 인덱스 없음 | 1,325.6 ms |
| `(user_id, score)` 후 | **14.3 ms** |

```
-> Covering index skip scan for grouping on solo_record using idx_exp_user_score  (actual time=0.0217..12.1 rows=10000)
```
`Covering` = 인덱스만 읽고 테이블 접근 없음. `skip scan for grouping` = 그룹마다 점프.

**그런데 서비스 쿼리(users JOIN)는 587 ms** — 인덱스가 있어도 조인 순서(users 바깥)는 그대로라 200만 행 조인은 남는다. 인덱스와 조인 순서는 별개의 병목.

### 2-3b. 쿼리 재작성 — 집계를 먼저, 조인은 나중에

```sql
SELECT u.id, u.nickname, t.best
FROM (SELECT user_id, MAX(score) AS best FROM solo_record GROUP BY user_id) t   -- 파생 테이블: skip scan → 1만 행
JOIN users u ON u.id = t.user_id                                                -- 1만 번의 PK 조회
ORDER BY t.best DESC
```
```
-> Nested loop inner join  (actual time=15.9..22.8 rows=10000)
    -> Sort: t.best DESC → Materialize
        -> Covering index skip scan for grouping on solo_record using idx_exp_user_score  (rows=10000)
    -> Single-row index lookup on u using PRIMARY (id=t.user_id)  (actual time=533e-6 rows=1 loops=10000)
```
**29.1 ms.** 재작성만 하고 인덱스가 없으면 1,388 ms — **인덱스와 재작성은 각각 다른 병목을 없앤다.** 둘 다 필요.

JPQL은 조인 순서를 강제할 수 없고 파생 테이블 표현이 불편해 **네이티브 쿼리**로 내렸다(`SoloRecordRepository.findAllTimeRanking`). 별칭(`userId`, `nickname`, `bestScore`)이 `RankingRow` 인터페이스 프로젝션과 일치해야 한다.

### 2-4. 주간 랭킹 `(created_at, user_id, score)`

최근 7일 범위 156,743 / 2,019,026 행.

| | 주간 집계 |
|---|---|
| 인덱스 없음 | 1,414.8 ms — `idx_solo_record_user_id` 전체 스캔 후 created_at Filter |
| `(created_at, user_id, score)` 후 | **42.5 ms** — `Covering index range scan over (created_at >= …)` |

created_at이 선두라 범위를 인덱스에서 자르고, user_id·score가 뒤에 있어 집계까지 커버링. 주간은 그룹 수가 유동적이라 skip scan 대신 range scan + 임시 테이블 집계인데도 충분히 빠르다.

### 2-5. 적용 결과 (V3 마이그레이션 + 네이티브 쿼리)

| 서비스 캐시 미스 경로 | 전 | 후 |
|---|---|---|
| 전체 (DB 집계 + 1만 명 ZADD) | 1,829 ms | **80.0 ms** |
| 주간 | — | **51.8 ms** |

남은 80ms의 대부분은 1만 명 ZADD warm-up. 인덱스 도입 비용: `solo_record` INSERT당 보조 인덱스 3개 갱신 — 기록은 판당 1회 INSERT라 감당 가능(E5 참고). 기존 `(user_id)`는 내 기록 커서 조회용으로 유지(InnoDB 보조 인덱스에 PK가 붙어 `ORDER BY id DESC`까지 커버).

---

## E3. 대전 전적 N+1 — 쿼리 수를 센다

Hibernate `generate_statistics=true` → `Statistics.getPrepareStatementCount()`로 SQL 실행 수를 직접 센다(p6spy 없이).

| 방식 | 페이지 크기 | SQL 실행 수 |
|---|---|---|
| 순진한 구현 (지연 로딩: 행마다 `mp.getMatch()`, 상대 조회) | 20 | **42** |
| 현재 구현 (`JOIN FETCH match` + 상대 `IN` 1회 + `GROUP BY` 집계) | 20 | **3** |

순진한 구현의 내역: 페이지 1 + 판(game_match) 20 + 상대(match_player) 20 + 상대 유저 지연 로딩 ≈ 42. 페이지가 50이면 102. 현재 구현은 **페이지 크기와 무관하게 3** — #28의 설계가 수치로 확인됐다.

**N+1을 보는 법** — 로그에서 같은 모양의 SELECT가 반복되면 의심. 이 실험처럼 통계로 세면 확실하다. 해결은 ① fetch join(연관 하나) ② `IN` 묶음(다대다·역방향) ③ `@EntityGraph` ④ batch size — 여기서는 ①+②.

---

## E4. 인증 필터의 "요청당 SELECT 1번" — 측정 → 개선 → 트레이드오프

### 4-1. 얼마나 붙어 있었나

Step 3에서 A안(토큰에 userId만, 필터가 매 요청 `findById`로 role 확인)을 택하며 비용 측정을 Step 14로 미뤄뒀다.

| 요청 | SQL 수 (전) | 그중 인증 | SQL 수 (후) |
|---|---|---|---|
| `GET /api/rankings/solo` 비로그인 | 1 | 0 | 1 |
| `GET /api/rankings/solo` 로그인 | 2 | **1** | **1** |
| `GET /api/users/me` | 2 | **1** | **1** |

users PK 조회 단건: 2.7 ms(중앙값, 앱→Docker MySQL 왕복 포함). "고작 1번"이지만 **모든 인증 요청에 무조건 붙는 1번**이다.

### 4-2. 부하에서의 실체 (k6, 200 VU 램프 50초, 같은 엔드포인트를 비로그인/로그인으로)

| | 처리량 | p50 | p95 | p99 | Hikari active 최대 |
|---|---|---|---|---|---|
| 비로그인 (SQL 1) | 1,406 req/s | 68 ms | 142 ms | 236 ms | 10/10 |
| 로그인, 개선 전 (SQL 2) | **840 req/s** | 110 ms | **231 ms** | 315 ms | 10/10 |
| 로그인, 개선 후 (SQL 1) | **1,265 req/s** | 77 ms | **154 ms** | 254 ms | 10/10 |

**읽는 법** — 처리량 비율 1406 : 840 ≈ 1.67 ≈ 요청당 SQL 수 비율 2 : 1에 가깝다. 커넥션 풀(기본 10)이 양쪽 다 포화(active 10)했으니 병목은 "DB 왕복 횟수". 인증 SELECT 하나를 빼자 처리량 +51%, p95 −33%. 남은 비로그인과의 차이(1,411 vs 1,265)는 로그인 시 추가되는 Redis `ZREVRANK`/`ZSCORE`(myRank)와 JWT 파싱.

### 4-3. 개선안 비교와 선택

| 안 | 내용 | 장점 | 단점 |
|---|---|---|---|
| A (기존) | 매 요청 DB 조회 | 권한 변경·탈퇴 즉시 반영 | 모든 인증 요청에 SELECT 1번 |
| **B (채택)** | `role`을 액세스 토큰 클레임에 | DB 왕복 0. 코드 단순 | 권한 변경·차단이 토큰 만료(30분)까지 미반영 |
| C | 조회 결과를 Redis 캐시(짧은 TTL) | 즉시 무효화 가능 | 여전히 Redis 왕복 1번, 캐시 무효화 코드 필요 |

B를 고른 이유: 이 서비스에 "즉시 차단"이 필요한 운영 요구가 없고, 재발급(refresh)이 DB를 읽으므로 30분 내 따라잡는다. 즉시 차단이 필요해지면 C(또는 블랙리스트)로 보완 — 그때의 비용은 "요청당 Redis 1번"이다.

**구현** — `JwtTokenProvider.createAccessToken(userId, role)`에 `role` 클레임, 필터는 서명 검증 + 클레임 파싱만. `CustomUserDetails`는 User 엔티티 대신 `(userId, role)`만 갖는다(엔티티를 들고 있으면 인증마다 DB 조회가 강제된다). 전환기 호환: role 클레임이 없는 옛 토큰은 DB 조회로 폴백.

---

## E5. 대량 삽입 — JPA saveAll vs JdbcTemplate batch

| 방식 (10,000건) | 소요 |
|---|---|
| JPA `saveAll` (IDENTITY 전략 → INSERT마다 왕복, 배치 불가) | 3,486 ms |
| `JdbcTemplate.batchUpdate` + `rewriteBatchedStatements=true` | **110 ms** (31.7×) |

IDENTITY는 INSERT 후에야 PK를 알 수 있어 Hibernate가 배치를 못 묶는다. JDBC batch는 `rewriteBatchedStatements`가 있어야 `INSERT … VALUES (…),(…),…` 한 문장으로 재작성된다 — 없으면 batch도 건별 전송(CI에서 더미 생성이 25분 걸렸던 원인, #26).

---

## 다시 해보기 체크리스트

1. 로컬 MySQL/Redis 기동 → `SOLO_DUMMY=true ./gradlew test --tests SoloRecordDummyGenerator` (200만 건, 수 분)
2. `DB_EXPERIMENT=true ./gradlew test --tests DbPerformanceExperimentTest` → `build/reports/db-experiment.md`
   - E2의 "인덱스 없음" 수치를 재현하려면 먼저 `DROP INDEX idx_solo_record_user_score, idx_solo_record_created_user_score` (V3 이전 상태)
3. 부하: `./gradlew bootRun` → 회원가입/로그인으로 토큰 → `k6 run -e MODE=anon load/auth-cost.js`, `-e MODE=auth -e TOKEN=…`
   - 실행 중 `curl localhost:8080/actuator/prometheus | grep hikaricp_connections_active`로 풀 포화 확인
4. 실행계획은 `EXPLAIN ANALYZE <sql>` — `actual time`, `rows`, `Covering`, `skip scan`, `Filter`를 읽는다 (`docs/index_experiment.md` "EXPLAIN ANALYZE 읽는 법")
