# Step 15 — 안정화 · 부하 테스트 기록

> 2026-09-04 · 로컬 Windows 11 · Docker MySQL 8.4 / Redis 7 · `solo_record` 2,017,382건 / 유저 10,004명 (Step 14 더미)
> k6는 Docker(`grafana/k6`)로 실행, 앱은 `java -jar` 로컬 프로필. 숫자는 이 노트북 기준 **before/after 상대 비교**용.
> 재현: `load/*.js` 상단 주석 참고. 서버 관찰은 p6spy 로그(쿼리별 ms)와 `/actuator/prometheus`의 `hikaricp_connections_*`.
>
> ⚠ **JVM 워밍업 주의** — 같은 시나리오를 방금 기동한 JVM에서 돌리면 JIT 전이라 2~3배 느리다
> (아래 miss 시나리오: 콜드 warm-up 3.7초 / 웜 585ms). 표의 숫자는 전부 한 번 이상 돌려 데운 뒤 측정한 값이다.

## 0. 한 장 요약

| 시나리오 | 발견 | 전 | 후 | 조치 |
|---|---|---|---|---|
| S1 랭킹 캐시 미스 폭주 (200 VU) | **캐시 스탬피드** — 집계가 커넥션 풀 크기만큼 중복 실행 | 집계 10회 × 1.7s, p95 **2.02s** | 집계 1회 × 0.6s, p95 **0.98s** | warm-up 단일 실행 락 (Redis `SET NX`) |
| S1' 같은 것, 풀 50으로 키우면 | 풀은 해법이 아니라 **증폭기** | 집계 50회 × 8.3s, p95 **11.7s** | — | 풀 기본값(10) 유지 |
| S2 같은 이메일 100명 동시 가입 | 선 조회 검사를 뚫고 UNIQUE 제약에 걸린 요청이 **500** | 201×1 / 409×90 / **500×9** | 201×1 / 409×99 / 500×0 | `DataIntegrityViolationException` → 409 |
| S3 고유 이메일 가입 램프 (50 VU) | BCrypt가 처리량 상한 — DB는 요청당 ~4ms | 50 req/s, p95 969ms | (의도된 비용, 변경 없음) | 문서화 |
| S4 20방 40명 동시 clear 폭주 | Lua 경로 정합성 실소켓 검증 | 2,312건 중 중복 제거 **0** | — | 통과, 변경 없음 |
| S0 랭킹 캐시 히트 (200 VU 램프) | 평상시 상한 | 958 req/s, p95 197ms | 1,060 req/s, p95 239ms | 기준선 (차이는 실행 편차) |

---

## S1. 랭킹 캐시 미스 폭주 — 캐시 스탬피드

**시나리오** — `ranking:solo:alltime`·`:warmed` 키를 지운 직후 200 VU가 동시에 `GET /api/rankings/solo`를 5회씩. 캐시 만료 직후, 또는 Redis 재시작 직후의 트래픽을 흉내 낸다.

**가설** — 캐시 미스를 본 요청이 각자 warm-up(200만 건 집계 + ZADD 1만 건)을 실행할 것이다. 요청 수만큼.

**관찰 (수정 전, 풀 10)**

| 항목 | 값 |
|---|---|
| `source=db` 로그 줄 수 (= 실행된 집계) | **10** |
| 집계 1건 소요 | 1,703 ~ 1,708 ms |
| 처리량 | 236 req/s |
| 지연 med / p95 / p99 / max | 271 ms / **2.02 s** / 2.38 s / 3.0 s |
| hikari active / pending 피크 | 10 / **141** |

200이 아니라 딱 **10**이었다. 이유는 HikariCP 기본 풀 크기가 10이고, `getRanking`에 걸린 `@Transactional(readOnly)` 때문에 요청이 진입하자마자 DB 커넥션을 잡기 때문이다(Hibernate가 트랜잭션 begin 시점에 autocommit을 끄려고 커넥션을 획득한다). 커넥션을 잡은 10개가 집계를 시작하고, 나머지 190개는 풀에서 대기(pending 141). 대기가 풀릴 즈음엔 플래그가 서 있어 Redis 경로로 빠진다. **풀이 우연히 스탬피드의 상한 노릇을 하고 있었다.**

**그러면 풀을 키우면?** — 부하에서 커넥션 대기가 보이면 풀을 키우는 게 흔한 반사 반응이다. `--spring.datasource.hikari.maximum-pool-size=50`으로 같은 시나리오:

| 항목 | 풀 10 | 풀 50 |
|---|---|---|
| 실행된 집계 | 10 | **50** |
| 집계 1건 소요 | 1.7 s | **8.3 s** (같은 쿼리 50개가 MySQL에서 경합) |
| 처리량 | 236 req/s | **63 req/s** |
| p95 / max | 2.02 s / 3.0 s | **11.7 s / 12.8 s** |
| hikari pending 피크 | 141 | 149 |

풀을 5배 키우니 집계가 5배 실행되고 각각 5배 느려졌다. 대기(pending)는 그대로다. **병목은 커넥션 수가 아니라 "같은 일을 여러 번 하는 것"**이다.

**수정** — warm-up 단일 실행(single-flight) 락. `RankingService.loadWithSingleFlight`:
1. `SET ranking:solo:alltime:warmup-lock 1 NX EX 30` 획득에 성공한 요청만 집계 + `bulkLoad`, `finally`에서 락 삭제
2. 실패한 요청은 50ms 간격으로 `:warmed` 플래그를 폴링(최대 5초) → 서면 Redis 경로로 응답
3. 5초를 넘기면(보유자 사망 등) 적재 없이 DB에서 직접 응답, 락은 TTL로 풀린다

락을 Redis에 둔 이유: `synchronized` 같은 로컬 락은 인스턴스가 2대가 되면 2번, N대면 N번 실행된다(INFRA.md Phase 4).
함께 `getRanking`의 서비스 레벨 `@Transactional`을 뗐다 — 대부분 Redis에서 끝나는 메서드가 폴링 내내 커넥션을 쥐고 있을 이유가 없다. 필요한 DB 조회는 각 repository 메서드가 자기 읽기 트랜잭션에서 짧게 한다.

**관찰 (수정 후, 풀 10)**

| 항목 | 전 | 후 |
|---|---|---|
| 실행된 집계 | 10 | **1** (585 ms) |
| 처리량 | 236 req/s | **412 req/s** |
| med / p95 / p99 / max | 271 ms / 2.02 s / 2.38 s / 3.0 s | 363 ms / **984 ms** / 1.06 s / 1.12 s |
| hikari active / pending 피크 | 10 / 141 | **5 / 5** |

p95가 2배, max가 2.7배 줄고 커넥션 대기는 사실상 사라졌다. 남은 지연(med 363ms)은 199개 대기자가 플래그가 선 순간 한꺼번에 깨어나 닉네임 IN 쿼리를 치는 구간이다.

**테스트** — `RankingWarmUpSingleFlightTest`: 캐시를 비우고 20스레드가 동시에 조회 → `findAllTimeRanking()` 호출 횟수 1(`@MockitoSpyBean` verify), 응답 20개 모두 테스트 유저 3명 포함, 락 키 반납 확인.

**트레이드오프** — 대기자는 최대 5초 블로킹된다(그동안 Tomcat 스레드 점유). 집계가 5초를 넘는 규모가 되면 폴링 대신 "stale 캐시를 먼저 주고 백그라운드 재적재"(stale-while-revalidate)로 가야 한다. 지금 규모(단독 집계 0.6초)에서는 단순한 쪽을 택했다.

---

## S2. 같은 이메일 100명 동시 가입 — UNIQUE 제약이 잡았을 때의 응답

**시나리오** — 100 VU가 동일 이메일·닉네임으로 동시에 1회 `POST /api/auth/signup`.

**가설** — `existsByEmail` → INSERT 사이의 틈을 여러 요청이 동시에 통과하고, UNIQUE 제약이 한 명만 남긴다. 문제는 제약에 걸린 쪽의 응답.

**관찰 (수정 전)**

| 응답 | 건수 | 경로 |
|---|---|---|
| 201 | 1 | INSERT 성공 |
| 409 | 90 | 선 조회 `existsByEmail`이 잡음 |
| **500** | **9** | 선 조회를 통과 → INSERT → `Duplicate entry ... uk_users_nickname` → `DataIntegrityViolationException` → 핸들러 없음 → COMMON500 |

Step 2에서 "제약이 최후의 방어선"이라고 적어뒀는데, **방어선이 작동했을 때의 응답이 500**이었다. 프론트는 "서버 오류"를 띄우고, 로그에는 스택트레이스가 9벌 쌓인다. 실제로는 "먼저 온 쪽이 이겼다"는 정상 상황이다.

**수정**
- `AuthService.signup`: `saveAndFlush`로 감싸 제약 위반을 그 자리에서 잡고, 제약 이름으로 `AUTH409`(email) / `USER409`(nickname)를 던진다 — 선 조회가 잡았을 때와 같은 코드
- `GlobalExceptionHandler`: `DataIntegrityViolationException` → `COMMON409` 공통 방어선 (닉네임 변경 등 다른 경로도 커버)

**관찰 (수정 후)** — 201×1 / 409×99 / 500×0. 로그는 4xx 규칙대로 warn 한 줄.

---

## S3. 고유 이메일 가입 램프 — BCrypt가 상한

**시나리오** — 0→20→50 VU 램프, 50초, VU마다 고유 이메일.

| 항목 | 값 |
|---|---|
| 처리량 | **50 req/s** |
| avg / med / p95 / p99 / max | 554 ms / 566 ms / 969 ms / 1.25 s / 1.76 s |
| p6spy `insert into users` | 2,621건, 평균 **2.6 ms** |
| p6spy `select ... where email` | 평균 **0.7 ms** |

요청당 DB 시간은 ~4ms인데 응답은 550ms. 나머지는 `BCryptPasswordEncoder.encode`(기본 strength 10 ≈ 코어당 60~100ms)가 CPU를 나눠 쓰는 시간이다. 50 VU가 동시에 해싱하면 코어 수로 나뉘어 한 요청이 500ms를 넘긴다.

**판단** — 손대지 않는다. BCrypt가 느린 건 목적(무차별 대입 비용)이다. strength를 낮추면 처리량은 오르지만 보안 예산을 깎는 것이고, 가입은 유저당 1번이라 랭킹 조회처럼 폭주하는 엔드포인트가 아니다. 규모가 커지면 레버는 **인스턴스 수(수평 확장)** 또는 **가입 엔드포인트 rate limit**이지 해시 강도가 아니다. p6spy로 "DB가 아니다"를 3초 만에 확정한 것이 이 실험의 소득.

---

## S4. 20방 40명 동시 clear 폭주 — 실소켓 정합성

**시나리오** — `load/clear-burst.js`. setup에서 유저 40명·방 20개를 REST로 만들고, VU 40개가 `/ws/websocket`에 STOMP로 붙어 ready → `GAME_START`의 보드에서 합 10 사각형을 전부 열거 → 두 플레이어가 **같은 목록을 같은 순서로** 즉시 발사(방당 최대 150건). 각 VU는 방 브로드캐스트를 전부 받으므로 "같은 칸이 두 번 지워졌는가"를 클라이언트에서 검증한다.

| 항목 | 값 |
|---|---|
| 방 / 플레이어 / GAME_START 수신 | 20 / 40 / 40 |
| 발사 | 2,312 |
| 성공 `APPLES_CLEARED` | 693 |
| 거절 `ALREADY_TAKEN` | 1,619 |
| 거절 `INVALID_SUM` / `INVALID_RANGE` / 기타 | 0 / 0 / 0 |
| **같은 칸 중복 제거** | **0** |
| 첫 응답까지 avg / p95 | 605 ms / 1.03 s |
| 발사 → 마지막 응답 avg / max | 1.03 s / 1.35 s |
| WS 핸드셰이크 | ~193 ms |

Step 10의 `AppleClearConcurrencyTest`(스레드 2개)가 증명한 것이 STOMP 인바운드 채널 → Lua → 브로드캐스트 전 경로, 방 20개 동시에서도 성립한다. 성공+거절 = 발사 수, 즉 유실도 없다.

2,312건이 1.35초 안에 처리됐다(≈1,700 msg/s). 첫 응답 600ms는 20방 × 150건이 한꺼번에 인바운드 채널 큐(기본 코어×2 스레드)에 쌓인 시간이다 — 실제 플레이(초당 몇 건)에서는 나올 수 없는 극단이라 튜닝 대상이 아니다.

---

## 함께 넣은 것 — p6spy

`com.github.gavlyukovskiy:p6spy-spring-boot-starter`. 로그 한 줄에 `실행시간 ms | 종류 | 바인딩된 SQL`.
`show-sql`은 파라미터가 `?`로 남고 시간이 없어 부하 분석에는 쓸모가 없다 — local에서 show-sql을 끄고 이걸 쓴다.
prod는 `decorator.datasource.enabled=false`로 프록시 자체를 뺀다. 이 문서의 S3 판단이 p6spy 한 번으로 나왔다.

## 남긴 것

- 랭킹 대기자 폴링(최대 5초)은 집계가 커지면 stale-while-revalidate로 교체
- 가입 rate limit — 부하 자체보다 봇 가입 방지가 이유가 될 때
- Redis 다운 시나리오(랭킹·방 상태 전부 Redis) — `timeout: 3s`가 요청을 얼마나 빨리 실패시키는지 미측정
- 여기 숫자는 노트북 1대 기준. VM(INFRA.md 예산)에서 다시 재면 절대치는 다르다 — 비율만 옮겨 볼 것
