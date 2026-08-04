# 🔧 리팩터링 기록

> 구현을 마친 뒤 발견한 문제와 그 해결을 기록한다. 항목마다 **증상 → 원인 → 해결**의 세 단으로 짧게 남긴다.
> `story.md`가 "무엇을 만들 것인가"라면, 이 문서는 "만들고 나서 무엇이 잘못됐는가"다.

| # | 제목 | 분류 | 상태 |
|---|---|---|---|
| 1 | [솔로 모드 기록 유실 — 틱 타이머와 수동 제출](#1-솔로-모드-기록-유실--틱-타이머와-수동-제출) | 안정성 | 🔴 예정 |
| 2 | [솔로 모드 제한시간 우회 — 서버가 시각을 검증하지 않음](#2-솔로-모드-제한시간-우회--서버가-시각을-검증하지-않음) | 보안 | 🔴 예정 |

> ⚠️ **적용 순서: 1 → 2.** 1번을 건너뛰고 2번을 넣으면 타이머가 밀린 정직한 유저의 move가 치팅으로 오판되어 거절된다(2번 원인 참고).

---

## 1. 솔로 모드 기록 유실 — 틱 타이머와 수동 제출

### 증상

정직하게 플레이했는데 기록이 저장되지 않고 사라진다. 다음 상황에서 발생한다.

- 게임 중 **다른 탭을 보거나 창을 최소화**했다가 돌아왔을 때
- 노트북 **절전**에 들어갔다 깨어났을 때
- 제출이 **한 번 실패**한 뒤 30초 안에 다시 누르지 못했을 때

서버 응답은 `SOLO404`(존재하지 않거나 만료된 세션)이고, 유저에게는 "기록 제출 실패"만 보인다.

### 원인

**클라이언트는 틱을 세고, 서버는 벽시계를 센다.**

```js
// solo.js:59-67 — 남은 시간을 재는 게 아니라 setInterval이 몇 번 불렸는지를 센다
timerId = setInterval(() => {
    remaining -= 1;
    if (remaining <= 0) { clearInterval(timerId); finish('시간 종료'); }
}, 1000);
```

`setInterval(fn, 1000)`은 "1초마다"가 아니라 "최소 1초 뒤에" 부른다는 뜻이다. 브라우저는 백그라운드 탭의 타이머 호출을 크게 지연시키고 절전 중에는 아예 멈추므로, **호출이 밀린 만큼 오차가 영구히 누적된다.** 120틱을 다 세는 시점이 벽시계로 180초일 수 있다.

반면 서버 세션 TTL 150초는 벽시계로 정확히 흐른다(`SoloGameService.java:28`). 그래서 자동 제출이 발사되는 순간 세션은 이미 만료 상태다.

여기에 두 가지가 겹친다.

- 서버는 moves를 받아야 채점할 수 있으므로, **제출되지 않은 게임은 존재하지 않은 게임**이 된다. 만료돼도 서버가 대신 정산해줄 수 없다.
- 유일한 수동 복구 수단인 `그만하기` 버튼도 TTL 30초 안에서만 유효한데, UI는 "버튼으로 다시 제출할 수 있습니다"라고 무제한인 것처럼 안내한다(`solo.js:93`).

### 해결

시간 계산을 **벽시계(`Date.now()`)로 통일**하고, 제출을 사람 손에서 떼어낸다. `elapsedMs`가 이미 `Date.now()` 기반(`solo.js:72`)이므로 시간축도 함께 맞춰진다.

#### (1) `solo.js` — 카운트다운을 실제 경과 시간으로 계산

```js
function startTimer(limit) {
    clearInterval(timerId);
    renderTimer($('soloTimer'), limit);
    timerId = setInterval(() => {
        // 틱을 세지 않고 startedAt(solo.js:48)에서 실제 경과를 매번 다시 계산 → 오차가 누적되지 않는다
        const remaining = limit - Math.floor((Date.now() - startedAt) / 1000);
        renderTimer($('soloTimer'), remaining);
        if (remaining <= 0) {
            clearInterval(timerId);
            finish('시간 종료');
        }
    }, 250);   // 게임 길이와 무관해졌으므로 간격을 좁혀 복귀 시 즉시 판정
}
```

#### (2) `solo.js` · `index.html` — 수동 제출 제거, 자동 제출만 남김

- `index.html:94-96`의 `그만하기 (기록 제출)` 버튼 블록 삭제
- `solo.js:26`의 클릭 리스너 삭제
- `solo.js:75`의 `if (board.isEmpty()) finish('보드 클리어 🎉');` 삭제 → 보드를 다 지워도 제한시간까지 진행 (빈 보드 대기 안내는 `soloStatus`에 표시)

#### (3) `solo.js` — 버튼이 사라진 자리를 자동 재시도로 대체

```js
async function finish(reasonText, attempt = 1) {
    ...
    } catch (e) {
        // 세션 없음(404)·중복 제출(409)은 재시도해도 결과가 같다
        const retryable = e.code !== 'SOLO404' && e.code !== 'SOLO409';
        if (retryable && attempt < 3) {
            $('soloStatus').textContent = `제출 실패 — 재시도 중 (${attempt}/3)…`;
            submitting = false;
            setTimeout(() => finish(reasonText, attempt + 1), 1000 * attempt);
            return;
        }
        $('soloStatus').textContent = `기록 제출 실패: ${e.message}`;
        submitting = false;
    }
}
```

### 확인

새로고침 후 게임 시작 → **다른 탭에서 1분 이상 머물다** 돌아온다. 돌아오는 즉시 남은 시간이 정확히 보정되고, 120초가 지났다면 바로 자동 제출되어 기록이 저장되면 정상이다.

### 남는 한계

게임 도중 **탭을 닫거나 새로고침하면 기록은 그대로 사라진다.** `gameSessionId`와 moves가 JS 메모리에만 있기 때문이다. 막으려면 `sessionStorage` 복구가 필요한데, 빈도 대비 비용이 커서 지금은 감수한다.

---

## 2. 솔로 모드 제한시간 우회 — 서버가 시각을 검증하지 않음

### 증상

제한시간 120초를 넘겨 플레이한 점수가 그대로 랭킹에 저장된다. 브라우저 콘솔만으로 재현된다.

기록에는 **`play_time_seconds`가 120으로 잘려서** 저장되므로(`SoloGameService.java:80-82`), "120초 안에 이 점수를 냈다"는 앞뒤가 맞지 않는 행이 랭킹에 남는다.

### 재현 코드

> ⚠️ 본인 로컬 서버에 본인 계정으로만 실행할 것. 재현으로 생긴 기록은 확인 후 DB에서 지운다.

**A. 타이머 무력화 — 실제 사용자 시나리오** (1번 적용 전, `그만하기` 버튼이 있을 때)

솔로 게임을 **시작한 직후** 콘솔에 붙여넣는다.

```js
/* [재현 A] 카운트다운 인터벌을 죽여 자동 제출을 막는다 → 제한시간 이후에도 계속 플레이 가능
   되돌리기: __exploitOff()   완전 초기화: 새로고침(F5) */
(() => {
    if (window.__exploitOff) window.__exploitOff();   // 중복 실행 시 이전 것부터 정리

    const el = document.getElementById('soloTimer');
    const originalText = el.textContent;
    const started = Date.now();

    // 지금 살아있는 모든 인터벌을 정지시킨다 (솔로 카운트다운뿐 아니라 다른 화면의 타이머도 함께 멈춘다)
    const probeId = setInterval(() => {}, 1 << 30);
    for (let i = 1; i <= probeId; i++) clearInterval(i);

    const displayId = setInterval(() => {
        const sec = Math.floor((Date.now() - started) / 1000);
        el.textContent = `경과 ${sec}s / 제한 120s`;
        el.classList.toggle('warn', sec > 120);
        // 세션 TTL을 넘기면 제출해도 SOLO404라 재현이 실패한다 → 조용히 실패하지 않도록 알리고 스스로 종료
        if (sec > 150) {
            console.warn('[A] 세션 TTL 초과 — 지금 제출하면 SOLO404입니다. 처음부터 다시 하세요.');
            window.__exploitOff();
        }
    }, 1000);

    window.__exploitOff = () => {
        clearInterval(displayId);
        el.textContent = originalText;
        el.classList.remove('warn');
        delete window.__exploitOff;
        console.log('[A] 정리 완료.');
    };

    console.log('[A] 타이머 무력화 — 120초가 지나도 게임이 끝나지 않습니다.');
    console.log('[A] 130~140초에 "그만하기" 버튼으로 제출한 뒤 __exploitOff() 를 실행하세요.');
})();
```

**결과**: 130~140초에 제출해도 서버가 수락하고, 120초 이후에 지운 사과의 점수까지 기록에 포함된다.

**B. API 직접 호출 — 결정적 재현** (수정 전후 모두에서 실행해 비교)

```js
/* [재현 B] UI를 거치지 않고 API를 직접 호출한다. 좌표는 서버가 준 보드에서 실제로 합이 10인
   조합만 고르므로 좌표 검증은 통과하고, 시각만 제한시간 밖(140초)으로 조작한다. */
(async () => {
    const s = await apiFetch('/api/solo/games', { method: 'POST' });
    const b = s.board.map(row => [...row]);
    const moves = [];

    for (let r = 0; r < b.length; r++) {
        for (let c = 0; c + 1 < b[r].length; c++) {
            if (b[r][c] + b[r][c + 1] === 10) {
                moves.push({ r1: r, c1: c, r2: r, c2: c + 1, elapsedMs: 140000 });
                b[r][c] = b[r][c + 1] = 0;
                c++;
            }
        }
    }

    try {
        const res = await apiFetch(`/api/solo/games/${s.gameSessionId}/finish`, { method: 'POST', body: { moves } });
        console.log('[B] 수락됨 ← 취약점 재현 성공', res);
    } catch (e) {
        console.log(`[B] 거절됨 ← 수정 반영됨 (${e.code}) ${e.message}`);
    }
})();
```

A는 실제 유저가 겪을 수 있는 형태를, B는 **프론트를 아무리 고쳐도 막을 수 없다**는 사실을 보여준다. 1번을 적용해 `그만하기` 버튼이 사라지면 A는 쓸 수 없으므로, 이후 검증은 B로 한다.

### 원인

`finish()`가 좌표는 재검증하지만 **시각은 전혀 보지 않는다.**

| 검증 항목 | 현재 |
|---|---|
| 보드가 서버가 준 것인가 (시드 재구성) | ✅ |
| 좌표가 유효한가 (범위·합10·중복 제거) | ✅ |
| 세션 주인·중복 제출 | ✅ |
| **move가 제한시간 안에 일어났는가** | ❌ |

`SoloReqDTO.Move`에 `elapsedMs`가 이미 들어오고 있지만(`SoloReqDTO.java:24`) 어디서도 읽지 않는다. 제한시간을 강제하는 주체가 클라이언트 타이머뿐이고, 클라이언트 코드는 사용자 손안에 있어 강제력이 없다. **규칙을 강제할 수 있는 것은 서버뿐이다.**

> 1번을 먼저 적용해야 하는 이유가 여기 있다. 틱 타이머를 그대로 둔 채 이 검증만 넣으면, 탭을 잠시 비웠던 정직한 유저의 move가 `elapsedMs = 135000`처럼 정직하게 기록되어 치팅으로 거절된다. 화면은 "40초 남음"인데 서버는 치팅이라고 답하는 상황이 된다.

### 해결

#### (1) `SoloErrorCode` — 전용 코드 추가

```java
    INVALID_MOVE_TIME(HttpStatus.BAD_REQUEST, "SOLO400_1", "제한시간을 벗어난 기록이 포함되어 있습니다."),
```

#### (2) `SoloGameService` — 재생 루프에서 시각 검증

```java
    private static final long TIME_LIMIT_MILLS = TIME_LIMIT_SECONDS * 1000L;

    // finish() 재생 루프 (현재 68-77행)
    long serverElapsedMills = System.currentTimeMillis() - session.getStartedAtMills();
    long previousElapsedMills = 0;

    for (SoloReqDTO.Move move : request.moves()) {
        validateMoveTime(move.elapsedMs(), previousElapsedMills, serverElapsedMills);
        previousElapsedMills = move.elapsedMs();

        int removed = board.clear(move.r1(), move.c1(), move.r2(), move.c2());
        ...
    }

    /** elapsedMs는 클라이언트가 보낸 값이므로 "믿는" 게 아니라 "모순을 잡아내는" 용도다. */
    private void validateMoveTime(long elapsedMills, long previousElapsedMills, long serverElapsedMills) {
        // ① 제한시간 밖 — 타이머를 무력화하고 계속 플레이한 경우
        if (elapsedMills < 0 || elapsedMills > TIME_LIMIT_MILLS) {
            throw new CustomException(SoloErrorCode.INVALID_MOVE_TIME);
        }
        // ② 시간 역전 — 정상 클라이언트는 시간순으로만 쌓는다 (solo.js:72)
        if (elapsedMills < previousElapsedMills) {
            throw new CustomException(SoloErrorCode.INVALID_MOVE_TIME);
        }
        // ③ 아직 오지 않은 미래 — 서버가 잰 세션 경과보다 뒤일 수 없다 (즉시 제출 봇 차단)
        if (elapsedMills > serverElapsedMills) {
            throw new CustomException(SoloErrorCode.INVALID_MOVE_TIME);
        }
    }
```

#### (3) `SoloGameService` — 세션 TTL을 제한시간에서 분리

`+30초`는 원래 "늦은 제출 허용"과 "제한시간 강제"를 겸직하던 값이라, 관용을 넓히면 치팅 창도 넓어지는 딜레마가 있었다. ②의 검증이 강제를 가져가면 TTL은 **키 청소와 늦은 제출 허용**만 담당하면 되므로 넉넉히 잡는 쪽이 낫다.

```java
    // 제한시간 강제는 validateMoveTime()이 담당한다
    private static final int SESSION_TTL_SECONDS = 600;   // 10분
```

TTL이 길어져도 더 오래 플레이할 수는 없다(①이 막는다). 세션 하나는 수백 바이트라 메모리 비용도 무시할 수준이다.

#### (4) `docs/API.md` 3-2 에러 표에 행 추가

```markdown
| `SOLO400_1` | 제한시간을 벗어나거나 시간순이 아닌 move가 포함됨 (타이머 우회 치팅) |
```

### 확인

1. 수정 전 **재현 B** 실행 → `수락됨` 확인 (콘솔 출력을 기록으로 남긴다)
2. 수정 후 **재현 B** 재실행 → `거절됨 (SOLO400_1)` 확인
3. **정상 플레이 회귀 확인** — 재현 코드 없이 120초를 꽉 채워 플레이 → 기록이 정상 저장되는지 확인 (③이 정상 유저를 막지 않는지 보는 가장 중요한 단계)
4. 회귀 테스트 추가: 제한시간 초과·음수·시간 역전·미래 시각 네 가지가 `SOLO400_1`로 거절되는지

### 남는 한계

`elapsedMs`는 클라이언트가 보낸 값이라, **145초 플레이한 뒤 타임스탬프를 120초 안으로 압축해 위조하면 세 검증을 모두 통과한다.**

| 공격 | 차단 |
|---|---|
| 타이머를 죽이고 계속 플레이 | ✅ ① |
| move 배열을 임의로 조립·재정렬 | ✅ ② |
| 시작 직후 "다 풀었다"고 즉시 제출 | ✅ ③ |
| 실제 시각을 제한시간 안으로 위조 | ❌ |

마지막 칸을 막는 유일한 방법은 매 move를 서버가 실시간 판정하는 것(대전 모드가 Step 10에서 택하는 방식)이고, 대가는 게임당 수십 번의 요청이다. 솔로 모드는 **랭킹 오염을 실용적으로 막는 선까지만** 투자하고 그 이상은 인지한 채 감수한다.

---

## 작성 형식

새 항목은 위 표에 한 줄 추가하고 아래 형식으로 이어 쓴다.

```markdown
## N. 제목

### 증상
(언제 무엇이 잘못되는가 — 사용자가 겪는 현상으로)

### 원인
(왜 그렇게 되는가 — 파일:줄 번호와 함께)

### 해결
(어느 파일의 무엇을 어떻게 바꾸는가)

### 확인
(고쳐졌는지 + 정상 케이스가 여전히 되는지)

### 남는 한계
(의도적으로 감수한 것)
```
