# 🍎 MyAppleGame — 사과게임 (1인 기록 도전 & 2인 실시간 대전)

합이 **10**이 되도록 사과를 드래그해서 지우는 사과게임입니다.
혼자서 최고 기록에 도전하는 **솔로 모드**와, WebSocket 기반으로 같은 보드에서 실시간으로 경쟁하는 **2인 대전 모드**를 제공합니다.

> 이 프로젝트는 **동시성 제어 · WebSocket 실시간 통신 · DB 성능 개선**을 실전 상황에서 학습하기 위해 기획되었습니다.
> 게임 규칙 자체는 단순하지만, "두 명이 같은 사과를 동시에 집으면?", "랭킹 조회가 느려지면?" 같은 문제를 직접 만들고 해결하는 것이 목표입니다.

---

## 1. 게임 규칙

| 항목 | 내용 |
|---|---|
| 보드 | 17 × 10 그리드, 각 칸에 1~9 숫자 사과 |
| 조작 | 마우스 드래그로 직사각형 영역 선택 |
| 성공 조건 | 선택 영역 내 사과 숫자의 합이 정확히 **10** |
| 점수 | 지운 사과 개수만큼 득점 |
| 제한 시간 | 120초 |

### 모드
- **솔로 모드** : 혼자 플레이하고 기록(점수/플레이 시간)을 저장. 개인 기록 추이와 전체 랭킹 제공.
- **대전 모드 (2인)** : 두 플레이어가 **동일한 보드**를 공유하며 실시간 경쟁. 같은 사과를 먼저 지운 사람이 점수를 가져감. 종료 시 승/패 기록.

---

## 2. 기술 스택

| 구분 | 기술 |
|---|---|
| Language / Runtime | Java 21 |
| Framework | Spring Boot 3.4.1 (Web, WebSocket, Security, Validation) |
| 인증 | JWT (jjwt) + 일반 회원가입/로그인 (BCrypt) — 소셜 로그인(OAuth2) 미사용 |
| ORM / DB | Spring Data JPA, MySQL |
| Cache / 실시간 상태 | Redis (Spring Data Redis) |
| API 문서 | SpringDoc OpenAPI (Swagger UI) |
| Frontend | Vanilla JS + HTML/CSS (정적 리소스 서빙) |
| Build | Gradle |

---

## 3. 학습 목표와 설계 반영 지점

### 3-1. 동시성 (Concurrency)

대전 모드의 핵심 문제: **두 플레이어가 같은 사과 영역을 거의 동시에 지우려고 할 때, 정확히 한 명만 성공해야 한다.**

| 학습 주제 | 반영 지점 |
|---|---|
| 경쟁 상태(Race Condition) 재현 | 공유 보드에서 동시 드래그 → 중복 득점 버그를 의도적으로 재현 후 해결 |
| 단일 스레드 직렬화 | 방(Room) 단위로 이벤트를 직렬 처리하는 구조 (per-room queue / synchronized) |
| Redis 원자 연산 | 보드 상태를 Redis에 두고 Lua Script로 "검증 + 제거"를 원자적으로 처리 |
| 분산 락 | Redisson `RLock` / `SETNX` 기반 락과 Lua 스크립트 방식의 성능 비교 |
| DB 락 | 게임 결과 정산 시 낙관적 락(`@Version`) vs 비관적 락(`SELECT FOR UPDATE`) 비교 |
| 검증 | 동시 요청 통합 테스트 (`ExecutorService` + `CountDownLatch`)로 정합성 검증 |

### 3-2. WebSocket

| 학습 주제 | 반영 지점 |
|---|---|
| STOMP 프로토콜 | `/ws` 엔드포인트 + `/topic/room/{roomId}` 구독 구조 |
| 세션 관리 | 접속/이탈 감지(`SessionDisconnectEvent`), 재접속 처리, 유령 세션 정리 |
| 브로드캐스트 설계 | 보드 변경분(delta)만 전송 vs 전체 스냅샷 전송 트레이드오프 |
| 하트비트 & 타임아웃 | 상대방 이탈 시 몰수승 처리 |
| 인증 연동 | WebSocket 핸드셰이크 시 인증 정보 전달 (Interceptor) |

### 3-3. DB 성능 개선

| 학습 주제 | 반영 지점 |
|---|---|
| 인덱스 설계 | 랭킹 조회(`ORDER BY score DESC`), 기간별 기록 조회에 인덱스 적용 전/후 `EXPLAIN ANALYZE` 비교 |
| N+1 문제 | 대전 기록 + 플레이어 조회 시 fetch join / `@EntityGraph` 적용 |
| 페이지네이션 | 랭킹 offset 방식 vs no-offset(커서) 방식 성능 비교 |
| 캐싱 전략 | 랭킹을 Redis **Sorted Set**으로 관리 — DB 집계 쿼리와 응답 시간 비교 |
| 쓰기 부하 분산 | 게임 중 실시간 상태는 Redis, 종료 시에만 DB에 결과 영속화 (write-behind) |
| 대용량 데이터 | 더미 기록 100만 건 삽입 후 쿼리 튜닝 실습 |

---

### 3-4. 인증 설계

**이메일/비밀번호 기반 일반 회원가입·로그인만 사용합니다. 소셜 로그인(OAuth2)은 구현했다가 제거했습니다.**

```
[일반 회원가입/로그인]
  프론트 ── email/password ──► 백엔드 (BCrypt 검증) ──► JWT(액세스/리프레시) 발급
  프론트 ── Authorization: Bearer ──► 백엔드 (REST)
  프론트 ── STOMP CONNECT 헤더 ──► 백엔드 (WebSocket)
```

#### OAuth2를 쓰지 않는 이유

초기에는 카카오·네이버 인가코드 직접 교환 방식의 소셜 로그인을 구현했지만, **테스트와 실제 사용자 확보 관점에서 일반 로그인이 더 낫다고 판단해 제거했습니다.**

- **테스트 비용** — 소셜 로그인은 인가 페이지 리다이렉트가 끼어들어 자동화가 어렵습니다. 부하 테스트(k6)나 동시성 통합 테스트에서 계정 N개를 즉시 만들어 토큰을 받는 흐름이 필요한데, 일반 회원가입은 `POST /api/auth/signup` → `POST /api/auth/login` 두 번으로 끝납니다.
- **가입 장벽** — 소셜 로그인은 심사·검수를 통과한 앱 등록과 도메인/redirect URI 확정이 전제입니다. 아직 도메인이 유동적인 단계에서 외부 플랫폼 설정에 묶이는 비용이 실제 사용자 유입 이득보다 컸습니다.
- **동의 항목 부담** — 이메일·닉네임을 받기 위해 동의 항목을 요구해야 하고, 그 자체가 이탈 요인이 됩니다. 반면 이 프로젝트는 동시성·DB 성능이 학습 주제이지 인증 연동이 주제가 아닙니다.
- **결론** — 인증은 JWT + BCrypt로 단순하게 유지하고, 학습·측정 대상인 게임 로직과 인프라에 집중합니다.

다만 `users.provider` 컬럼(`LOCAL`)은 남겨두어, 이후 소셜 로그인을 다시 붙일 때 스키마 마이그레이션 없이 확장할 수 있게 했습니다. 현재 모든 계정은 `LOCAL`이며, 로그인 시 `provider != LOCAL`이면 거부합니다.

## 4. 아키텍처 개요

```
[Browser]
   │  REST (회원/기록/랭킹/방 관리)
   │  WebSocket-STOMP (대전 실시간 이벤트)
   ▼
[Spring Boot]
   ├─ REST API ──────────────► MySQL (회원, 게임 기록, 대전 결과 — 영속 데이터)
   ├─ WebSocket Handler
   │      └─ Game Engine ───► Redis (진행 중인 보드 상태, 방 정보, 락, 랭킹 캐시)
   └─ 게임 종료 시 Redis → MySQL 결과 정산 (write-behind)
```

- **진행 중인 게임 상태는 Redis**, **끝난 게임의 기록은 MySQL** — 역할을 명확히 분리합니다.
- 대전 모드의 사과 제거 검증은 Redis 원자 연산(Lua)으로 처리해 동시성 문제를 해결합니다.

---

## 5. 문서

| 문서 | 위치 |
|---|---|
| ERD (엔티티 관계도) | [docs/ERD.md](docs/ERD.md) |
| API 명세서 (REST + WebSocket) | [docs/API.md](docs/API.md) |
| Swagger UI | 서버 실행 후 `http://localhost:8080/swagger-ui/index.html` |

---

## 6. 실행 방법

```bash
# MySQL, Redis 실행 (Docker Desktop이 켜져 있어야 함)
docker compose up -d

# 로컬 설정 파일 생성 (최초 1회) — DB 접속 정보를 본인 환경에 맞게 수정
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml

# 애플리케이션 실행 (기본 프로필: local)
./gradlew bootRun
```

접속: `http://localhost:8080`

---

## 7. 프로젝트 구조

```
src/main/java/com/apple/game
├── BackendApplication.java
├── domain
│   ├── auth        # 인증 (일반 회원가입/로그인, JWT 발급·재발급)
│   ├── user        # 회원
│   ├── solo        # 솔로 모드 기록
│   ├── room        # 대전 방 관리
│   ├── match       # 대전 진행/결과
│   └── ranking     # 랭킹 조회 (Redis Sorted Set + DB)
└── global
    ├── apiPayload  # 공통 응답 포맷 (CustomResponse, 에러/성공 코드)
    ├── common      # BaseTimeEntity 등
    ├── config      # Security, Swagger, JPA Auditing, WebSocket, Redis
    └── exception   # 전역 예외 처리
```

---

## 8. 학습 로드맵 (마일스톤)

- [ ] **M0. 인증** — 일반 회원가입/로그인 (JWT + BCrypt). 소셜 로그인(OAuth2)은 테스트·사용자 확보 편의를 위해 제외
- [ ] **M1. 솔로 모드** — 보드 생성/검증 로직, 기록 저장, 기본 랭킹 (JPA + MySQL)
- [ ] **M2. 대전 모드 기본** — WebSocket(STOMP) 연결, 방 생성/입장, 보드 동기화
- [ ] **M3. 동시성 제어** — 동시 제거 경합 재현 → Lua 스크립트/분산 락으로 해결, 동시성 테스트 작성
- [ ] **M4. DB 성능 개선** — 더미 데이터 대량 삽입, 랭킹 쿼리 튜닝, Redis Sorted Set 랭킹 도입
- [ ] **M5. 안정화** — 재접속 처리, 몰수승, 유령 세션 정리, 부하 테스트 (k6/JMeter)
