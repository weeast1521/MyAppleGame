-- Step 14 DB 성능 실습 — 랭킹 집계용 인덱스 (근거: docs/db_performance.md E2, 200만 건 실측)
--  (user_id, score)            : 전체 랭킹 GROUP BY user_id MAX(score) → 커버링 skip scan   1,325ms → 14ms
--  (created_at, user_id, score): 주간 랭킹 created_at 범위 + GROUP BY   → 커버링 range scan  1,415ms → 43ms
-- 기존 (user_id)는 내 기록 커서 조회용으로 유지한다(보조 인덱스에 PK가 붙어 id 정렬까지 커버).
CREATE INDEX idx_solo_record_user_score ON solo_record (user_id, score);
CREATE INDEX idx_solo_record_created_user_score ON solo_record (created_at, user_id, score);
