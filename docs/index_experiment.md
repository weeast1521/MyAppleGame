# solo_record 인덱스 성능 실험(커서 페이지네이션)

> 2026-08-11 / MySQL 8.4.11 / solo_record 약 200만 건 (유저 10,000명, 유저당 1~400건)
> 측정 대상: 내 기록 조회 커서 페이지네이션 쿼리 (`SoloRecordRepository.findPageByUserId`)
> 실험 유저: user_id = 5000 (기록 118건)

## 대상 엔티티

```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "solo_record")
public class SoloRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int score;

    @Column(name = "cleared_count", nullable = false)
    private int clearedCount;       // 10 조합 성공 횟수

    @Column(name = "play_time_seconds", nullable = false)
    private int playTimeSeconds;    // 실제 플레이 시간

    @Column(name = "board_seed", nullable = false)
    private String boardSeed;       // 보드 재현용 시드
}
```

측정 쿼리 (JPA가 생성하는 형태와 동일, 첫 페이지는 커서 자리에 `Long.MAX_VALUE`가 들어간다):

```sql
SELECT * FROM solo_record
WHERE user_id = 5000 AND id < 999999999
ORDER BY id DESC LIMIT 21;
```

---

## 사전 지식

### 디스크에는 트리가 두 개 있다

InnoDB에서 이 테이블의 물리 구조는 B-tree 두 개다.

```
[PK 트리 (클러스터드 인덱스 = 테이블 본체)]     [보조 인덱스 idx_solo_record_user_id]
id 순서로 정렬, 행 전체가 들어 있음              (user_id, id) 순서로 정렬,
                                              항목엔 user_id와 id만 들어 있음
(id=1)    user_id=17, score=88, ...            (user_id=1, id=201)
(id=2)    user_id=17, score=52, ...            (user_id=1, id=202)
...                                            ...
(id=2백만) user_id=9998, ...                    (user_id=5000, id=987001)  ← 이 유저의
                                               (user_id=5000, id=987002)     118건이
                                               ...                           뭉쳐 있음
```

- "풀 스캔"의 실체는 PK 트리를 처음부터(또는 역순으로) 걷는 것이다. 걷는 방향은 자유다.
- **보조 인덱스의 숨은 PK**: `(user_id)`로 선언해도 리프에 PK가 붙어 실체는 `(user_id, id)`다.
  같은 user_id 구간 안은 id 순서로 정렬돼 있다 — 이 실험 전체를 관통하는 사실.
- 보조 인덱스에는 행 전체가 없으므로 `SELECT *`를 채우려면 인덱스에서 얻은 id로
  **PK 트리에 되찾아가야** 한다. 이 lookup 비용을 어떻게 다루느냐가 각 계획의 갈림길이다.

### EXPLAIN ANALYZE 읽는 법

트리는 **가장 안쪽(들여쓰기 깊은 줄)부터 실행**되고 결과가 바깥으로 흐른다. 각 노드의 괄호 두 묶음:

- `(cost=92 rows=118)` — 실행 **전** 옵티마이저의 **예측**. cost는 내부 비용 단위(ms 아님),
  rows는 이 노드가 내보낼 것으로 예상한 행 수.
- `(actual time=1.51..1.69 rows=118 loops=1)` — 실행 **후** **실측**. `A..B`는 첫 행까지 A ms,
  마지막 행까지 B ms. 부모 노드의 시간은 **자식 시간을 포함한 누적값**이다.
- 예측 rows와 실측 rows가 크게 어긋나면 옵티마이저가 잘못된 계획을 고르는 원인이 된다
  (통계 갱신은 `ANALYZE TABLE`).

### 반복 실행하면 빨라지는 이유 — 버퍼 풀 워밍업

InnoDB는 데이터를 16KB 페이지 단위로 읽고 반드시 버퍼 풀(메모리 캐시)을 거친다.
1회차(콜드)는 디스크 I/O가 섞이고, 2회차부터(웜)는 순수 메모리 탐색이라 시간이 뚝 떨어진다.
계획이 바뀌는 게 아니라 I/O가 사라지는 것. **측정은 첫 회를 버리고 수렴한 값**으로 했다.

---

## 결과 요약

| 계획 | 인덱스 상태 | 읽은 행 | actual time | 상대 속도 |
|---|---|---|---|---|
| ③ PK 역순 스캔 (인덱스 없는 세계) | 보조 인덱스 미사용 | 1,100,000 | 339ms | ×3,100 |
| ① 복합 인덱스 + Sort | `(user_id, created_at DESC)` | 118 | 1.7ms | ×16 |
| ② index merge + Sort (기본 선택) | `(user_id)` | 118 | 1.0ms | ×9 |
| ④ 보조 인덱스 역방향 스캔 (이상) | `(user_id)` + FORCE INDEX | **21** | **0.109ms** | 기준 |

**결론**

- 인덱스 유무(③ ↔ 나머지)가 **3,000배**를 가르고, 인덱스가 쿼리의 WHERE·ORDER BY와
  모양이 맞는지(①② ↔ ④)가 다시 **10배**를 가른다.
- **실행계획은 쿼리에 고정된 속성이 아니라 "쿼리 × 인덱스 구성 × 통계"의 함수다.**
  같은 쿼리가 인덱스 교체만으로 ①→②로, 힌트 하나로 ②→③→④로 바뀌었다.
- 옵티마이저가 항상 최선을 고르지는 않는다(② ↔ ④). 비용 모델은 디스크 I/O 가정 위에
  서 있어서, 데이터가 전부 메모리에 있으면 판단이 뒤집힐 수 있다. 다만 기본 선택도
  1ms라 실용상 문제 없으므로 JPA 쿼리에 힌트는 넣지 않는다.
- 옵티마이저의 행 수 예측은 **균등 분포 가정** 위에 있다(③에서 예측 35만 vs 실측 110만).
  데이터가 뭉쳐 있으면 베팅이 크게 빗나간다.

---

## 계획 ① — 복합 인덱스 `(user_id, created_at DESC)` + filesort

실험 시작 시점의 인덱스. 인덱스 안에서 user_id=5000 구간은 **created_at 내림차순**으로
늘어서 있는데, 쿼리가 원하는 순서는 **id 내림차순**(id는 solo_record의 PK)이다.
꺼낸 순서 ≠ 원하는 순서이므로 118건을 메모리에서 재정렬해야 했다 — 그게 Sort 노드다.

"나중에 만든 기록이 id도 크고 created_at도 최신이니 같은 순서 아닌가?"라는 의문이 들지만:

1. DB는 두 컬럼의 상관관계를 **가정할 수 없다**. created_at은 그냥 값이 든 컬럼일 뿐,
   id 순서와 일치한다는 보장이 스키마 어디에도 없다. 옵티마이저는 인덱스가 **선언한
   순서**만 믿는다.
2. 실제로 이 더미 데이터에선 두 순서가 완전히 다르다 — id는 삽입 순서대로,
   created_at은 최근 90일 사이 랜덤으로 넣었기 때문에 Sort가 실제로 순서를 크게 바꿨다.

```sql
EXPLAIN ANALYZE
SELECT * FROM solo_record
WHERE user_id = 5000 AND id < 999999999
ORDER BY id DESC LIMIT 21;
```

```text
-> Limit: 21 row(s)  (cost=92 rows=21) (actual time=1.74..1.74 rows=21 loops=1)
    -> Sort: solo_record.id DESC, limit input to 21 row(s) per chunk  (cost=92 rows=118) (actual time=1.74..1.74 rows=21 loops=1)
        -> Index lookup on solo_record using idx_solo_record_user_created (user_id=5000), with index condition: (solo_record.id < 999999999)  (cost=92 rows=118) (actual time=1.51..1.69 rows=118 loops=1)
```

읽는 법 메모:

- `Index lookup (user_id=5000)` — 인덱스로 그 유저 구간에 점프해 118건 전부 읽음 (1.69ms).
- `with index condition: (id < ...)` — 숨은 PK 덕에 `id < cursor`를 테이블 본체까지 안 가고
  인덱스 안에서 거름 (Index Condition Pushdown).
- `Sort` — 정렬 자체는 1.74 − 1.69 ≈ 0.05ms. `limit input to 21 rows per chunk`는 완전
  정렬 대신 상위 21건만 유지하는 top-N 정렬 최적화. 지금은 싸지만 **유저의 기록 수에
  비례해 나빠지는 구조**다(기록 5만 건인 유저면 매 페이지 5만 건 읽기 + 정렬).

### 참고: 인덱스 방향(ASC/DESC)이 진짜 중요한 경우

- 동등 조건(`WHERE user_id = ?`)에 걸리는 컬럼의 방향은 **무의미**하다 — 어느 방향이든
  트리 탐색으로 구간에 점프하는 비용은 같다.
- MySQL은 인덱스를 역방향으로도 읽으므로(Backward index scan) **전부 반대 방향**은 같은
  인덱스로 커버된다: `(ASC, ASC)` ↔ `(DESC, DESC)`는 사실상 같은 인덱스.
- 방향이 결정적인 건 ORDER BY에서 컬럼 방향이 **엇갈릴 때**뿐:
  `ORDER BY a ASC, b DESC`는 `(a ASC, b DESC)` 혹은 `(a DESC, b ASC)` 없이는 정렬을 피할 수 없다.
- 원래 인덱스의 `created_at DESC`는 `ORDER BY created_at DESC`를 역방향 스캔(약간 느림)
  대신 정방향 스캔으로 처리하려는 미세 튜닝이었다 — 필수가 아니다.

## 인덱스 교체

FK 제약이 user_id로 시작하는 인덱스를 요구하므로 **생성을 먼저, 삭제를 나중에** 해야 한다
(순서를 바꾸면 `Cannot drop index ... needed in a foreign key constraint`).

```sql
CREATE INDEX idx_solo_record_user_id ON solo_record(user_id);
DROP INDEX idx_solo_record_user_created ON solo_record;
```

```text
+-------------+------------+-------------------------+--------------+-------------+-----------+-------------+
| Table       | Non_unique | Key_name                | Seq_in_index | Column_name | Collation | Cardinality |
+-------------+------------+-------------------------+--------------+-------------+-----------+-------------+
| solo_record |          0 | PRIMARY                 |            1 | id          | A         |     1975500 |
| solo_record |          1 | idx_solo_record_user_id |            1 | user_id     | A         |        9866 |
+-------------+------------+-------------------------+--------------+-------------+-----------+-------------+
```

이 교체로 이후 측정에서 **쿼리는 한 글자도 안 바뀌었는데 계획이 달라진다.** SQL은 "무엇을
원하는지"만 선언하고, "어떻게 가져올지"는 실행 시점에 그 순간 존재하는 인덱스들을 재료로
옵티마이저가 새로 결정하기 때문이다. 실무 시사점: 인덱스를 추가·삭제하면 손대지 않은
다른 쿼리들의 계획까지 바뀔 수 있으므로 주요 쿼리의 EXPLAIN을 다시 확인해야 한다.

## 계획 ② — index merge + filesort (교체 후 옵티마이저의 기본 선택)

교체 후 옵티마이저 앞에 놓인 후보들:

- (a) `(user_id)` 인덱스로 118건 읽고 정렬 — 계획 ①과 같은 모양
- (b) `(user_id)` 인덱스를 **역방향으로 21건만** 읽기 — 이상적 (→ 계획 ④에서 확인)
- (c) PK 역순 스캔하며 user_id 필터 — 인덱스를 안 쓰는 도박수 (→ 계획 ③에서 확인)
- (d) **index merge**: 두 조건의 교집합을 row ID 순으로 처리

옵티마이저는 (d)를 골랐다.

```sql
EXPLAIN ANALYZE
SELECT * FROM solo_record
WHERE user_id = 5000 AND id < 999999999
ORDER BY id DESC LIMIT 21;
```

```text
-> Limit: 21 row(s)  (cost=62.3 rows=21) (actual time=0.976..0.978 rows=21 loops=1)
    -> Sort: solo_record.id DESC, limit input to 21 row(s) per chunk  (cost=62.3 rows=59) (actual time=0.975..0.976 rows=21 loops=1)
        -> Filter: ((solo_record.user_id = 5000) and (solo_record.id < 999999999))  (cost=62.3 rows=59) (actual time=0.33..0.636 rows=118 loops=1)
            -> Intersect rows sorted by row ID  (cost=62.3 rows=59) (actual time=0.316..0.613 rows=118 loops=1)
                -> Index range scan on solo_record using idx_solo_record_user_id over (user_id = 5000 AND id < 999999999)  (cost=0.0104..1.23 rows=118) (actual time=0.0333..0.0561 rows=118 loops=1)
```

### index merge intersect란

MySQL의 기본 규칙은 "테이블 하나에 인덱스 하나"인데, AND로 묶인 조건들이 **서로 다른
인덱스**에 흩어져 있으면 어느 하나만 타도 나머지 조건은 행을 읽어보며 확인해야 한다.
index merge intersection은 이 한계를 우회한다: **각 인덱스에서 행 본체를 읽지 않고
행 ID 목록만 뽑아 교집합을 구한 뒤, 확정된 최소한의 행만 가져온다.**

교집합이 진가를 발휘하는 예 (단일 인덱스로는 조건 전부를 감당 못 할 때):

```
WHERE user_id = 5000 AND score > 160   -- 인덱스는 (user_id), (score) 따로만 존재

(user_id) 인덱스 → user_id=5000인 id들: {103, 250, 987001, 987005, ...}  118개
(score) 인덱스  → score>160인 id들:    {77, 250, 3001, 987005, ...}     수만 개
교집합          → 양쪽에 다 있는 id:    {250, 987005}                     2개!
→ PK 트리에서 딱 2건만 가져오면 끝
```

우리 쿼리에선 `user_id = 5000`(보조 인덱스)과 `id < cursor`(PRIMARY)가 형식상 다른
인덱스 소관이라 intersect 후보로 분류됐다. 그런데 실행계획의 Intersect 노드 아래에
스캔이 **하나뿐**이다 — 보조 인덱스에 PK가 이미 들어 있어 `id < cursor`를 PRIMARY를
따로 스캔할 필요 없이 보조 인덱스 항목에서 그 자리에서 검사할 수 있었기 때문. 교집합
기계는 돌았지만 실질은 "한 인덱스 스캔 + row ID 정렬 + 일괄 가져오기"만 남은
**퇴화된 intersect**다.

### 처리 과정과 트레이드오프

```
1. 보조 인덱스에서 user_id=5000 구간을 정방향으로 전부 읽음 → 매치 id 118개 확보
2. id들을 오름차순 정렬                       ← "Intersect rows sorted by row ID"
3. 정렬된 순서대로 PK 트리를 방문해 행을 가져옴 ← 트리를 한 방향으로 훑는 순차적 접근 (I/O 유리)
4. 손에 든 118행의 순서는 id ASC, 쿼리는 id DESC를 원함 → Sort로 재정렬
5. 상위 21건 반환
```

- 강점: 3단계. row ID를 정렬해두고 방문하면 PK 트리 접근이 순차화돼 **디스크** I/O에 유리하다.
- 약점: 교집합을 완성하려면 매치를 전부 모아야 하므로 **조기 종료 불가**(118건 다 읽음),
  row ID 순으로 재배열하며 원래 순서를 잃어 **Sort가 다시 필요**하다.

### 왜 (d)를 골랐고, 실제 승자는 (b)였나

비용 모델의 눈: (b)는 "랜덤 PK 조회 21번", (d)는 "정렬된 PK 조회 118번". 옵티마이저는
랜덤 접근을 디스크 I/O 기준으로 비싸게 치므로 (d)가 이긴다고 계산했다. 실제로는 데이터가
전부 버퍼 풀에 있었고, 메모리에선 랜덤/순차 비용이 같아 (d)의 강점이 증발 —
실측은 (b) 0.109ms가 (d) 0.97ms를 이겼다. 우리 쿼리는 `(user_id, id)` 인덱스 하나가
두 조건과 정렬까지 혼자 처리할 수 있었으므로 intersect는 애초에 불필요한 기계였다.

## 계획 ③ — PK 역순 스캔 (인덱스가 없는 세계의 최선)

index merge를 힌트로 끄자, 옵티마이저는 남은 후보 중 (c)를 골랐다 — `ORDER BY id DESC
LIMIT 21`을 보고 "정렬 순서를 공짜로 주는 PK를 역순으로 걷다가 21건 차면 금방 끝나겠지"
라는 베팅(`prefer_ordering_index` 휴리스틱). 보조 인덱스를 INVISIBLE로 껐을 때 나오는
계획과 동일하므로, **이 측정이 곧 "인덱스 없음" 상태의 수치**다.

```
1. PK 트리의 맨 끝(id 최대)으로 이동
2. 거꾸로 한 행씩 읽음 — 행 전체가 그 자리에 있으니 되찾아가기 없음, 순차 접근이라 행당 비용 최소
3. 행마다 "user_id = 5000인가?" 검사 → 맞으면 반환
4. 21건 차는 순간 중단 — 그런데 그게 언제인지는 데이터 분포(운)에 달렸다
```

```sql
EXPLAIN ANALYZE
SELECT /*+ NO_INDEX_MERGE(solo_record) */ *
FROM solo_record
WHERE user_id = 5000 AND id < 999999999
ORDER BY id DESC LIMIT 21;
```

```text
-> Limit: 21 row(s)  (cost=36.7e+6 rows=21) (actual time=339..339 rows=21 loops=1)
    -> Filter: ((solo_record.user_id = 5000) and (solo_record.id < 999999999))  (cost=36.7e+6 rows=17579) (actual time=339..339 rows=21 loops=1)
        -> Index scan on solo_record using PRIMARY (reverse)  (cost=36.7e+6 rows=351572) (actual time=0.0738..304 rows=1.1e+6 loops=1)
```

예측 `rows=351572`는 균등 분포 가정에서 나온 값이다: 매치 확률 118/2,000,000이니
21건을 모으려면 2,000,000 × 21/118 ≈ 35만 행. 실측은 **110만 행(테이블의 절반)** —
더미 생성기가 유저별로 몰아서 삽입해 이 유저의 기록이 id 공간 중간에 한 덩어리로
뭉쳐 있었고, 역순 스캔이 덩어리에 닿을 때까지 다른 유저의 행을 헛걸음했기 때문이다.
"행 하나는 싸지만 몇 행을 읽을지 모른다"가 이 계획의 본질이고, 실서비스에선
**오래전에 접은 유저**가 정확히 이 최악 케이스를 만든다.

## 계획 ④ — 보조 인덱스 역방향 스캔 (이상적인 계획)

```
1. 보조 인덱스에서 user_id=5000 구간의 "끝"(구간 안 id 최대)으로 트리 탐색 점프
2. 구간을 거꾸로 한 항목씩 읽음 — 구간 안이 이미 id 순서이므로 그대로 id DESC 순서
   = ORDER BY가 공짜, Sort 노드 없음
3. 항목마다 id로 PK 트리에서 행을 가져와 즉시 반환
4. 정렬이 없으니 LIMIT을 읽는 도중 적용 가능 → 21건 반환한 순간 모든 작업 중단
```

유저의 기록이 118건이든 10만 건이든 **항상 21건만 읽는다** — 비용이 데이터량과 무관하게 일정.

```sql
EXPLAIN ANALYZE
SELECT * FROM solo_record FORCE INDEX (idx_solo_record_user_id)
WHERE user_id = 5000 AND id < 999999999
ORDER BY id DESC LIMIT 21;
```

```text
-> Limit: 21 row(s)  (cost=1.42 rows=1) (actual time=0.0497..0.109 rows=21 loops=1)
    -> Index range scan on solo_record using idx_solo_record_user_id over (user_id = 5000 AND id < 999999999) (reverse), with index condition: ((solo_record.user_id = 5000) and (solo_record.id < 999999999))  (cost=1.42 rows=1) (actual time=0.0475..0.105 rows=21 loops=1)
```

`(reverse)` = 오름차순 인덱스의 역방향 스캔. Sort 노드가 없고 `rows=21`인 것이
"인덱스 순서 = 출력 순서 → 조기 종료 가능"의 물증이다.

---

## 남은 일

- [ ] `SoloRecord` 엔티티에 `@Table(indexes = @Index(name = "idx_solo_record_user_id", columnList = "user_id"))` 선언 — 현재 인덱스는 로컬 DB에만 존재하며, 선언해야 팀원 로컬·prod에도 생성된다 (`ddl-auto: update`는 추가만 하고 삭제는 안 하므로, 팀원 DB의 기존 `idx_solo_record_user_created`는 각자 수동 DROP 필요)
- [ ] Step 14에서 offset vs 커서, 기간별 조회(`created_at` 인덱스 재검토) 추가 실험
