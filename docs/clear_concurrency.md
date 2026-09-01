# 사과 제거 동시성 — 무방비 / 분산 락 / Lua 비교 (Step 10)

> 2026-09-01 / Redis 7 (Docker Desktop, macOS) / Spring Boot 3.4.1 + Lettuce, Redisson 3.50.0
> 측정 코드: `ClearExecutorBenchmarkTest` (`CLEAR_BENCH=true ./gradlew test --tests '*ClearExecutorBenchmarkTest*'`)
> 정합성 검증: `AppleClearConcurrencyTest`

## 문제

대전 중 두 명이 **같은 사과를 동시에 드래그**하면, 사과는 한 번 사라지는데 점수는 두 번 올라갈 수 있다.
사과 제거 한 번은 Redis 명령 4개로 이루어진다.

| 순서 | 명령 | 역할 |
|---|---|---|
| ① | `HGETALL room:{code}` | 게임 중인지, 방 멤버인지 |
| ② | `HMGET room:{code}:board r:c ...` | 선택 영역에 남은 사과와 합 (check) |
| ③ | `HDEL room:{code}:board r:c ...` | 사과 제거 (act) |
| ④ | `HINCRBY room:{code}:scores {userId} {n}` | 점수 가산 (act) |

명령 하나하나는 Redis가 원자적으로 처리하지만, **②와 ③ 사이에 상대의 요청이 끼어들 수 있다** — Step 8의 join 레이스와 같은 check-then-act 문제다.

```
A: HMGET → 합 10 ✔
B: HMGET → 합 10 ✔        ← A가 아직 지우기 전
A: HDEL, HINCRBY(A) +2
B: HDEL(이미 없음, 0개 삭제), HINCRBY(B) +2   ← 사과 2개에 점수 4점
```

## 세 가지 구현

세 구현은 모두 `ClearExecutor` 인터페이스(`tryClear(roomCode, userId, fields) → ClearOutcome`)를 만족하고, `application.yaml`의 `game.clear.strategy`로 고른다. 입력·결과는 같고 **틈을 어떻게 다루느냐만 다르다.**

### 1차 `NoLockClearExecutor` — 무방비
위 4개 명령을 그대로 순서대로 호출. 틈이 열려 있다. 버그 재현용으로 남겨둔다.

### 2차 `RedissonLockClearExecutor` — 분산 락으로 틈을 **잠근다**
```java
RLock lock = redissonClient.getLock("lock:room:" + roomCode + ":clear");
if (!lock.tryLock(500, 3000, MILLISECONDS)) return LOCK_TIMEOUT;
try { return noLock.tryClear(...); }      // 1차 로직 그대로
finally { lock.unlock(); }
```
- 1차 코드를 한 줄도 바꾸지 않고 앞뒤만 감쌌다. 같은 방의 요청은 락을 잡은 하나만 실행된다.
- JVM `synchronized`와 달리 서버가 여러 대여도 유효하다 (Redis 하나를 보고 락을 잡으니까).
- 비용: 락 획득(`SET NX PX` + 실패 시 pub/sub 대기)과 해제(Lua)가 붙어 왕복이 4 → 6회 이상. 경합 시 **대기 시간**이 더해진다.
- `leaseTime`(3초)은 락을 잡은 채 서버가 죽었을 때의 보험. `waitTime`(0.5초)을 넘기면 포기한다.

### 3차 `LuaClearExecutor` — 스크립트로 틈을 **없앤다** ← 최종 채택
```lua
-- KEYS: room, board, scores / ARGV: userId, r:c ...
if HGET room status ~= 'PLAYING' → NOT_PLAYING
if userId ∉ {hostId, guestId}   → NOT_MEMBER
values = HMGET board fields
sum, present = 남은 칸의 합과 목록      -- 없는 필드는 false로 온다
if sum > 10 → INVALID_SUM / if sum < 10 → ALREADY_TAKEN
HDEL board present... ; HINCRBY scores userId #present
return {'SUCCESS', present...}
```
- Redis는 단일 스레드로 명령을 처리하고, `EVAL`로 넘긴 스크립트는 통째로 **하나의 명령**이다. 스크립트가 도는 동안 다른 클라이언트의 명령은 실행되지 않으므로 check와 act 사이에 틈 자체가 없다.
- 왕복 1회, 대기 없음, 데드락 없음, 락 만료 같은 엣지 케이스 없음.
- 주의: 스크립트 안에서 오래 걸리는 일을 하면 Redis 전체가 멈춘다. 여기서는 최대 170칸 `HMGET`/`HDEL`이라 µs 단위.
- Step 8 `JOIN_SCRIPT`, Step 9 `READY_SCRIPT`와 같은 접근. 반환값이 여러 개(지운 칸 목록)라 문자열 대신 Lua 테이블로 돌려준다.

## 정합성 — `AppleClearConcurrencyTest`

host·guest 두 스레드를 `CountDownLatch`로 같은 순간에 출발시켜 **같은 영역(합 10)** 을 지운다. 매 라운드 새 보드, 20라운드 반복.

| 전략 | 둘 다 SUCCESS인 라운드 | 점수 합 (정합 = 40) | 결과 |
|---|---|---|---|
| NO_LOCK | **20 / 20** | **80** | ❌ 실패 (버그 재현) |
| REDISSON_LOCK | 0 / 20 | 40 | ✅ |
| LUA | 0 / 20 | 40 | ✅ |

NO_LOCK 실행 로그 (라운드마다 `[SUCCESS, SUCCESS]`):
```
[NO_LOCK] round 1 → [SUCCESS, SUCCESS]
...
[NO_LOCK] round 20 → [SUCCESS, SUCCESS]
[NO_LOCK] 집계: 중복 득점 라운드 20/20, 점수 합 80 (정합이면 40), 성공 횟수 {920002=20, 920001=20}
AssertionFailedError: [둘 다 SUCCESS를 받은 라운드 수 (0이어야 정합)] expected: 0 but was: 20
```
NO_LOCK 케이스는 항상 실패하므로 `@Disabled`로 CI에서 제외한다. 버그를 눈으로 보고 싶으면 로컬에서 애노테이션을 지우고 실행.

로컬(Docker) 환경은 Redis 왕복이 ~0.3ms로 느린 편이라 틈이 넓어 100% 재현됐다. 왕복이 빠른 환경에서는 재현율이 낮아질 뿐 문제가 사라지는 것은 아니다.

## 처리량 — `ClearExecutorBenchmarkTest`

보드 전체를 5로 채워 가로 인접 두 칸이 항상 합 10이 되게 하고, **요청마다 서로 다른 쌍**을 지운다 (사과 경합 없음 → 순수 왕복/락 비용 측정).

**순차 2,000건 (1스레드)**

| 전략 | 총 시간 | 평균 지연 / 건 | 처리량 | Redis 왕복 |
|---|---|---|---|---|
| LUA | 779 ms | **389 µs** | 2,567 ops/s | 1 |
| NO_LOCK | 2,555 ms | 1,277 µs | 782 ops/s | 4 |
| REDISSON_LOCK | 4,306 ms | 2,153 µs | 464 ops/s | 6+ |

평균 지연이 왕복 횟수에 거의 정비례한다 (왕복 1회 ≈ 0.3~0.35ms). 서버가 하는 일은 셋 다 비슷하고, **네트워크 왕복이 비용의 전부**다.

**동시 8스레드 2,000건 (같은 방)**

| 전략 | 총 시간 | 처리량 | 락 타임아웃 |
|---|---|---|---|
| LUA | 109 ms | **18,348 ops/s** | – |
| NO_LOCK | 348 ms | 5,747 ops/s | – |
| REDISSON_LOCK | 4,406 ms | 453 ops/s | 0 |

- LUA·NO_LOCK은 스레드를 늘린 만큼 처리량이 올라간다 (왕복을 병렬로 겹칠 수 있다).
- REDISSON_LOCK은 **순차와 거의 같은 처리량**이다. 방 단위 락이라 같은 방의 요청은 결국 한 줄로 서고, 8스레드는 대기만 늘린다. 방마다 락이 다르므로 방이 많으면 전체 처리량은 오르지만, 한 방 안의 상한은 순차 처리량이다.

## 결론

| | 무방비 | 분산 락 | Lua |
|---|---|---|---|
| 정합성 | ❌ | ✅ | ✅ |
| 왕복 | 4 | 6+ (+대기) | 1 |
| 같은 방 동시 처리 | 병렬 (틀린 결과) | 직렬 | 병렬 (Redis가 직렬화) |
| 실패 모드 | 중복 득점 | 락 타임아웃, lease 만료 | 없음 (스크립트 오류 시 전체 반환) |
| 적합한 경우 | – | 임계 구역에 **Redis 밖 작업**(DB 쓰기, 외부 호출)이 섞일 때 | 임계 구역이 **Redis 명령만**으로 닫힐 때 |

사과 제거는 임계 구역이 Redis 명령 4개로 닫히므로 Lua가 정답이다. 락은 "틈을 잠그고", Lua는 "틈을 없앤다" — 잠글 틈이 없는 쪽이 더 빠르고 더 단순하다.
분산 락이 필요한 순간은 Step 11의 정산처럼 Redis 읽기와 MySQL 쓰기가 한 임계 구역에 묶일 때다 (거기서는 `@Version` 낙관적 락으로 먼저 시도한다).

## 함께 넣은 것 — `requestId` 멱등 처리

WebSocket은 네트워크가 불안정하면 클라이언트가 같은 메시지를 재전송할 수 있고, 서버는 그것을 "정상적인 두 번째 clear"와 구분할 수 없다.
프론트가 요청마다 UUID(`requestId`)를 붙이고, 서버는 `SADD room:{code}:reqs {requestId}`의 반환값(새로 추가 1 / 이미 있음 0)으로 첫 도착만 통과시킨다. `SADD` 자체가 원자 명령이라 같은 requestId가 동시에 두 번 와도 하나만 1을 받는다. SET에는 TTL 5분(한 판 + 여유)을 걸고, 새 판 시작·방 정리 시 지운다.
