# API 명세서 — MyAppleGame

- Base URL: `http://localhost:8080`
- 인증: **JWT** — REST는 `Authorization: Bearer {accessToken}` 헤더, WebSocket은 STOMP CONNECT 헤더로 전달
- 로그인 수단 3종: 일반(email/password), 카카오, 네이버 — 소셜은 **프론트가 인가코드를 받아 백엔드에 전달**하는 방식
- 모든 REST 응답은 공통 포맷 `CustomResponse`를 따릅니다.

```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "성공적으로 처리되었습니다.",
  "result": { }
}
```

---

## 1. 인증 API

### 소셜 로그인 흐름 (카카오/네이버 공통)

```
1. 프론트: 인가 페이지로 이동
   카카오: https://kauth.kakao.com/oauth/authorize?client_id=...&redirect_uri=...&response_type=code
   네이버: https://nid.naver.com/oauth2.0/authorize?client_id=...&redirect_uri=...&response_type=code&state=...
2. 사용자 동의 → redirect_uri 로 인가코드(code) 수신 (프론트 정적 페이지)
3. 프론트: 인가코드를 백엔드 API로 전달 (1-3 / 1-4)
4. 백엔드: 토큰 교환 → 사용자 정보 조회 → 최초면 자동 회원가입 → JWT 발급
```

### 1-1. 일반 회원가입
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/auth/signup` |
| 인증 | 불필요 |

**Request**
```json
{
  "email": "user@example.com",
  "password": "P@ssw0rd!",
  "nickname": "사과왕"
}
```

**Response `result`**
```json
{ "userId": 1, "email": "user@example.com", "nickname": "사과왕" }
```

| 에러 코드 | 상황 |
|---|---|
| `AUTH4091` | 이미 가입된 이메일 |
| `USER4001` | 닉네임 중복 |
| `AUTH4002` | 비밀번호 형식 오류 (8자 이상, 영문+숫자+특수문자) |

### 1-2. 일반 로그인
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/auth/login` |
| 인증 | 불필요 |

**Request**
```json
{ "email": "user@example.com", "password": "P@ssw0rd!" }
```

**Response `result`**
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "user": { "userId": 1, "nickname": "사과왕", "provider": "LOCAL" }
}
```

| 에러 코드 | 상황 |
|---|---|
| `AUTH4011` | 이메일 또는 비밀번호 불일치 |
| `AUTH4092` | 소셜 계정으로 가입된 이메일 (해당 소셜 로그인 유도) |

### 1-3. 카카오 로그인 (인가코드 전달)
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/auth/login/kakao` |
| 설명 | 백엔드가 인가코드로 카카오 토큰 교환 → 사용자 정보 조회 → 최초면 자동 회원가입 후 JWT 발급 |
| 인증 | 불필요 |

**Request**
```json
{
  "code": "카카오가_발급한_인가코드",
  "redirectUri": "http://localhost:8080/oauth/kakao/callback.html"
}
```

**Response `result`** — 일반 로그인과 동일 (`provider: "KAKAO"`), 최초 가입 시 `isNewUser: true` 포함
```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "eyJhbGciOi...",
  "isNewUser": true,
  "user": { "userId": 2, "nickname": "kakao_3fa9", "provider": "KAKAO" }
}
```

| 에러 코드 | 상황 |
|---|---|
| `AUTH4012` | 유효하지 않거나 만료된 인가코드 |
| `AUTH5001` | 소셜 제공자 API 호출 실패 |

### 1-4. 네이버 로그인 (인가코드 전달)
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/auth/login/naver` |
| 설명 | 카카오와 동일 흐름. 네이버는 CSRF 방지용 `state` 검증 필요 |
| 인증 | 불필요 |

**Request**
```json
{
  "code": "네이버가_발급한_인가코드",
  "state": "프론트가_생성한_state값",
  "redirectUri": "http://localhost:8080/oauth/naver/callback.html"
}
```

**Response** — 1-3과 동일 (`provider: "NAVER"`)

### 1-5. 토큰 재발급
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/auth/reissue` |
| 인증 | 불필요 (refreshToken으로 검증) |

**Request**
```json
{ "refreshToken": "eyJhbGciOi..." }
```

**Response `result`**
```json
{ "accessToken": "eyJhbGciOi...", "refreshToken": "eyJhbGciOi..." }
```

| 에러 코드 | 상황 |
|---|---|
| `AUTH4013` | 만료되었거나 폐기된 리프레시 토큰 (재로그인 필요) |

### 1-6. 로그아웃
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/auth/logout` |
| 설명 | 리프레시 토큰 폐기 |
| 인증 | 필요 |

---

## 2. 회원 API

### 2-1. 내 정보 조회
| 항목 | 내용 |
|---|---|
| Method / URL | `GET /api/users/me` |
| 인증 | 필요 |

**Response `result`**
```json
{
  "userId": 1,
  "email": "user@example.com",
  "nickname": "사과왕",
  "provider": "google"
}
```

### 2-2. 닉네임 변경
| 항목 | 내용 |
|---|---|
| Method / URL | `PATCH /api/users/me/nickname` |
| 인증 | 필요 |

**Request**
```json
{ "nickname": "새닉네임" }
```

| 에러 코드 | 상황 |
|---|---|
| `USER4001` | 닉네임 중복 |
| `USER4002` | 닉네임 형식 오류 (2~12자) |

---

## 3. 솔로 모드 API

### 3-1. 게임 시작 (보드 발급)
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/solo/games` |
| 설명 | 서버가 보드를 생성해 시드와 함께 반환. 점수 검증을 위해 게임 세션을 Redis에 보관 |
| 인증 | 필요 |

**Response `result`**
```json
{
  "gameSessionId": "a1b2c3d4",
  "boardSeed": "550e8400",
  "board": [[7, 3, 9, "..."], ["..."]],
  "timeLimitSeconds": 120
}
```

### 3-2. 게임 종료 (기록 제출)
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/solo/games/{gameSessionId}/finish` |
| 설명 | 서버가 제출된 제거 이력을 시드 기반으로 재검증 후 기록 저장 (치팅 방지) |
| 인증 | 필요 |

**Request**
```json
{
  "moves": [
    { "r1": 0, "c1": 2, "r2": 1, "c2": 4, "elapsedMs": 3200 }
  ]
}
```

**Response `result`**
```json
{
  "recordId": 42,
  "score": 86,
  "maxCombo": 5,
  "isPersonalBest": true,
  "allTimeRank": 17
}
```

| 에러 코드 | 상황 |
|---|---|
| `SOLO4001` | 존재하지 않거나 만료된 게임 세션 |
| `SOLO4002` | 제출 기록 검증 실패 (합이 10이 아닌 move 포함 등) |
| `SOLO4091` | 이미 제출된 세션 (중복 제출 — 동시성 학습 포인트) |

### 3-3. 내 기록 조회
| 항목 | 내용 |
|---|---|
| Method / URL | `GET /api/solo/records/me` |
| Query | `cursor` (no-offset 페이지네이션), `size` (기본 20) |
| 인증 | 필요 |

**Response `result`**
```json
{
  "records": [
    { "recordId": 42, "score": 86, "maxCombo": 5, "playTimeSeconds": 118, "createdAt": "2026-07-28T14:00:00" }
  ],
  "nextCursor": 41,
  "hasNext": true
}
```

### 3-4. 내 통계 요약
| 항목 | 내용 |
|---|---|
| Method / URL | `GET /api/solo/records/me/summary` |
| 인증 | 필요 |

**Response `result`**
```json
{
  "bestScore": 112,
  "totalGames": 57,
  "averageScore": 74.3,
  "allTimeRank": 17
}
```

---

## 4. 랭킹 API

> Redis Sorted Set 캐시를 우선 조회하고, 미스 시 DB 집계 → 캐시 적재.
> `source` 필드로 어느 저장소에서 응답했는지 노출 (성능 비교 학습용).

### 4-1. 랭킹 조회
| 항목 | 내용 |
|---|---|
| Method / URL | `GET /api/rankings/solo` |
| Query | `period`: `alltime` \| `weekly` (기본 `alltime`), `offset` (기본 0), `size` (기본 20, 최대 100) |
| 인증 | 불필요 |

**Response `result`**
```json
{
  "period": "alltime",
  "source": "redis",
  "myRank": { "rank": 17, "score": 112 },
  "rankings": [
    { "rank": 1, "nickname": "사과왕", "score": 143 },
    { "rank": 2, "nickname": "합십장인", "score": 139 }
  ]
}
```

### 4-2. 대전 전적 조회
| 항목 | 내용 |
|---|---|
| Method / URL | `GET /api/matches/me` |
| Query | `cursor`, `size` (기본 20) |
| 인증 | 필요 |

**Response `result`**
```json
{
  "summary": { "totalMatches": 31, "wins": 18, "losses": 12, "draws": 1 },
  "matches": [
    {
      "matchId": 7,
      "result": "WIN",
      "myScore": 92,
      "opponentNickname": "합십장인",
      "opponentScore": 85,
      "finishedAt": "2026-07-28T15:30:00"
    }
  ],
  "nextCursor": 6,
  "hasNext": true
}
```

---

## 5. 대전 방 관리 API (REST)

> 방 생성/입장은 REST로, 입장 이후의 실시간 흐름은 WebSocket으로 처리합니다.

### 5-1. 방 생성
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/rooms` |
| 인증 | 필요 |

**Response `result`**
```json
{ "roomCode": "APPLE1", "status": "WAITING" }
```

### 5-2. 방 입장
| 항목 | 내용 |
|---|---|
| Method / URL | `POST /api/rooms/{roomCode}/join` |
| 설명 | 정원(2명) 검사 — **동시 입장 경합 처리 학습 포인트** (분산 락) |
| 인증 | 필요 |

**Response `result`**
```json
{
  "roomCode": "APPLE1",
  "status": "READY",
  "host": { "userId": 1, "nickname": "사과왕" },
  "guest": { "userId": 2, "nickname": "합십장인" }
}
```

| 에러 코드 | 상황 |
|---|---|
| `ROOM4041` | 존재하지 않는 방 |
| `ROOM4091` | 방이 가득 참 (동시 입장 시 한 명만 성공해야 함) |
| `ROOM4092` | 이미 게임이 진행 중인 방 |

### 5-3. 방 나가기
| 항목 | 내용 |
|---|---|
| Method / URL | `DELETE /api/rooms/{roomCode}/leave` |
| 설명 | 게임 시작 전/판 사이 퇴장. 남은 인원에게 `PLAYER_LEFT` 브로드캐스트 + **방 누적 점수 초기화**. 게임 중 이탈은 WebSocket 연결 종료로 감지 |
| 인증 | 필요 |

---

## 6. WebSocket (STOMP) 명세 — 대전 모드

### 연결 정보

| 항목 | 내용 |
|---|---|
| 엔드포인트 | `ws://localhost:8080/ws` (SockJS fallback 지원) |
| 구독 (수신) | `/topic/room/{roomCode}` — 방 전체 브로드캐스트 |
| 구독 (수신) | `/user/queue/errors` — 개인 에러 메시지 |
| 발행 (송신) | `/app/room/{roomCode}/...` |
| 인증 | STOMP CONNECT 프레임의 `Authorization: Bearer {accessToken}` 헤더 (ChannelInterceptor에서 검증) |

모든 브로드캐스트 메시지는 `type` 필드로 구분합니다.

### 6-1. 클라이언트 → 서버 (발행)

#### `/app/room/{roomCode}/ready` — 준비 완료
```json
{ }
```
- 첫 판 시작 전과 **매 판 종료 후 재대결 준비** 시 모두 사용. 두 명 모두 ready 상태가 되면 서버가 새 보드로 `GAME_START`를 브로드캐스트한다 (같은 방에서 연전).

#### `/app/room/{roomCode}/clear` — 사과 제거 시도 ⭐ 동시성 핵심
```json
{
  "requestId": "uuid-v4",
  "r1": 0, "c1": 2,
  "r2": 1, "c2": 4
}
```
- `requestId`: 멱등성 보장용. 재전송 시 중복 처리 방지.
- 서버는 Redis Lua Script로 **[존재 검증 → 합 10 검증 → 제거 → 점수 가산]** 을 원자적으로 실행.
- 성공 시 방 전체에 `APPLES_CLEARED` 브로드캐스트, 실패 시 요청자에게만 `CLEAR_REJECTED` 전송.

### 6-2. 서버 → 클라이언트 (브로드캐스트: `/topic/room/{roomCode}`)

#### `GAME_START` — 양쪽 준비 완료 시
```json
{
  "type": "GAME_START",
  "matchId": 7,
  "round": 2,
  "players": { "1": "사과왕", "2": "합십장인" },
  "board": [[7, 3, 9, "..."], ["..."]],
  "timeLimitSeconds": 120,
  "startAt": "2026-07-28T15:00:03Z"
}
```
- `startAt`: 서버 기준 시작 시각 (클라이언트 카운트다운 동기화).
- `round`: 이 방에서 몇 번째 판인지 (1부터 시작, 연전 시 증가).
- `players`: userId → 닉네임 매핑 (프론트가 상대 표시에 사용).

#### `APPLES_CLEARED` — 사과 제거 성공
```json
{
  "type": "APPLES_CLEARED",
  "clearedBy": 1,
  "cells": [ { "r": 0, "c": 2 }, { "r": 0, "c": 3 }, { "r": 1, "c": 4 } ],
  "scores": { "1": 34, "2": 28 }
}
```

#### `GAME_END` — 시간 종료 / 몰수
```json
{
  "type": "GAME_END",
  "reason": "TIME_UP",
  "round": 2,
  "scores": { "1": 92, "2": 85 },
  "totalScores": { "1": 180, "2": 173 },
  "winnerUserId": 1,
  "results": { "1": "WIN", "2": "LOSE" }
}
```
- `reason`: `TIME_UP` | `OPPONENT_LEFT` (몰수) | `ABORTED`
- `totalScores`: **이 방에서의 누적 점수** (판마다 합산). Redis에 방 단위로 보관하며, 한 명이라도 방을 나가면 초기화된다. 영속 저장 대상이 아니므로 MySQL에는 저장하지 않는다 (판별 기록은 `game_match`/`match_player`에 저장).
- `TIME_UP` 종료 후 두 플레이어가 다시 `/ready`를 보내면 같은 방에서 다음 판이 시작된다.

#### `PLAYER_LEFT` — 플레이어가 방을 나감 (게임 중이 아닐 때)
```json
{ "type": "PLAYER_LEFT", "userId": 2 }
```
- `DELETE /api/rooms/{roomCode}/leave` 또는 대기 중 연결 종료 시 남은 인원에게 브로드캐스트.
- 서버는 방의 **누적 점수(totalScores)와 판 카운트를 초기화**하고 방 상태를 `WAITING`으로 되돌린다 (새 상대 입장 가능).
- 게임 진행 중의 이탈은 `PLAYER_LEFT`가 아니라 `OPPONENT_STATUS` → (유예 초과 시) 몰수 `GAME_END`로 처리.

#### `OPPONENT_STATUS` — 상대 접속 상태 변화
```json
{ "type": "OPPONENT_STATUS", "userId": 2, "status": "DISCONNECTED", "graceSeconds": 15 }
```
- 연결 끊김 후 `graceSeconds` 내 재접속하면 `RECONNECTED`, 아니면 몰수 처리 → `GAME_END`.

### 6-3. 서버 → 요청자 개인 (`/user/queue/errors`)

#### `CLEAR_REJECTED` — 제거 실패
```json
{
  "type": "CLEAR_REJECTED",
  "requestId": "uuid-v4",
  "reason": "ALREADY_TAKEN"
}
```
- `reason`: `ALREADY_TAKEN` (상대가 먼저 지움 — 경합 패배) | `INVALID_SUM` | `INVALID_RANGE`

---

## 7. 공통 에러 코드

| HTTP | 코드 | 설명 |
|---|---|---|
| 400 | `COMMON400` | 잘못된 요청 (validation 실패) |
| 401 | `AUTH4001` | 인증 필요 |
| 403 | `AUTH4031` | 권한 없음 |
| 404 | `COMMON404` | 리소스 없음 |
| 409 | `COMMON409` | 상태 충돌 (동시성 경합 등) |
| 500 | `COMMON500` | 서버 내부 오류 |

---

## 8. 시퀀스 예시 — 대전 한 판의 흐름

```
Host                    Server                    Guest
 │  POST /api/rooms       │                         │
 │◄── roomCode ───────────│                         │
 │                        │   POST /rooms/{c}/join  │
 │                        │◄────────────────────────│
 │  WS 연결 + 구독         │        WS 연결 + 구독    │
 │─── /ready ────────────►│◄──────── /ready ────────│
 │◄══ GAME_START (브로드캐스트, 동일 보드) ══════════►│
 │                        │                         │
 │─── /clear (0,2)~(1,4) ►│◄─ /clear (0,2)~(1,4) ───│   ← 동시 요청!
 │                        │  Lua Script 원자 실행     │
 │◄══ APPLES_CLEARED (Host 득점) ═══════════════════►│
 │                        │── CLEAR_REJECTED ──────►│   (ALREADY_TAKEN)
 │                        │                         │
 │            ... 120초 경과 ...                     │
 │◄══ GAME_END (결과 + DB 정산 완료) ════════════════►│
```
