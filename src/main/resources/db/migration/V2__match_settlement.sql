-- Step 11 판 종료·정산 (write-behind)
--  1) game_match.version: 낙관적 락 컬럼 — 타이머와 이탈 처리가 동시에 정산을 시도해도 한쪽만 성공
--  2) match_player: 판별 플레이어 결과(점수·승패). Redis 휘발 점수의 영속화 대상

-- 기존 행은 0으로 채운다 — @Version은 NULL 행에 대해 UPDATE 매칭이 실패한다(version = NULL 비교)
ALTER TABLE game_match
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE match_player (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    match_id   BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    score      INT    NOT NULL,
    result     ENUM ('WIN','LOSE','DRAW','FORFEIT_WIN','FORFEIT_LOSE') NOT NULL,
    created_at DATETIME(6) DEFAULT NULL,
    updated_at DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id),
    -- 같은 판에 같은 유저 행이 두 번 들어갈 수 없다 — 중복 정산의 DB 레벨 최후 방어선
    UNIQUE KEY uk_match_player_match_user (match_id, user_id),
    -- 내 전적 조회(user_id 필터 + id 커서 내림차순)용 복합 인덱스
    KEY idx_match_player_user (user_id, id),
    CONSTRAINT fk_match_player_match FOREIGN KEY (match_id) REFERENCES game_match (id),
    CONSTRAINT fk_match_player_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
