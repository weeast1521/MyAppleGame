-- 초기 스키마 (Hibernate 6.6이 엔티티에서 생성한 DDL을 덤프해 정리한 것)
-- prod는 ddl-auto: validate 이므로, 엔티티가 바뀌면 반드시 새 V{n}__*.sql 마이그레이션을 추가해야 한다.
-- 컬럼 타입을 임의로 바꾸면 validate 단계에서 기동이 실패하니 주의.

CREATE TABLE users (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    provider    ENUM ('KAKAO','LOCAL','NAVER') NOT NULL,
    provider_id VARCHAR(255) DEFAULT NULL,
    email       VARCHAR(255) DEFAULT NULL,
    password    VARCHAR(100) DEFAULT NULL,
    nickname    VARCHAR(15)  NOT NULL,
    role        ENUM ('ADMIN','USER') NOT NULL,
    version     BIGINT DEFAULT NULL,
    created_at  DATETIME(6) DEFAULT NULL,
    updated_at  DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_nickname (nickname),
    UNIQUE KEY uk_users_provider (provider, provider_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE refresh_token (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    user_id    BIGINT NOT NULL,
    token      VARCHAR(512) NOT NULL,
    expires_at DATETIME(6)  NOT NULL,
    created_at DATETIME(6) DEFAULT NULL,
    updated_at DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_token (token)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE game_match (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    room_code   VARCHAR(255) NOT NULL,
    status      ENUM ('ABORTED','FINISHED','PLAYING') NOT NULL,
    board_seed  VARCHAR(255) NOT NULL,
    started_at  DATETIME(6)  NOT NULL,
    finished_at DATETIME(6) DEFAULT NULL,
    created_at  DATETIME(6) DEFAULT NULL,
    updated_at  DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_game_match_room_code (room_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE solo_record (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    user_id           BIGINT NOT NULL,
    score             INT NOT NULL,
    cleared_count     INT NOT NULL,
    play_time_seconds INT NOT NULL,
    board_seed        VARCHAR(255) NOT NULL,
    created_at        DATETIME(6) DEFAULT NULL,
    updated_at        DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_solo_record_user_id (user_id),
    CONSTRAINT fk_solo_record_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
