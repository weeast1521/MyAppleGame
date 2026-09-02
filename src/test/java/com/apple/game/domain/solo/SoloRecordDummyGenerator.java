package com.apple.game.domain.solo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * solo_record 인덱스 성능 실습용 더미 데이터 생성기 (수동 실행 전용).
 *
 * 실행 방법: SOLO_DUMMY=true ./gradlew test --tests SoloRecordDummyGenerator
 *           (IDE에서는 실행 구성에 환경변수 SOLO_DUMMY=true 추가)
 * 주의: solo_record를 TRUNCATE 하므로 기존 기록이 전부 삭제된다.
 *
 * 게이트를 @Disabled 주석 토글이 아니라 환경변수로 하는 이유:
 * 주석을 풀고 되돌리는 걸 잊은 채 커밋되면 매 CI가 200만 건 INSERT를
 * 실행한다 — 실제로 그렇게 커밋되어 CI test가 25분+ 걸렸다.
 * ClearExecutorBenchmarkTest(CLEAR_BENCH)와 같은 패턴.
 *
 * 전제: application-local.yaml의 JDBC URL에 rewriteBatchedStatements=true
 * (없으면 batch가 한 건씩 전송되어 수십 분이 걸린다)
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "SOLO_DUMMY", matches = "true")
class SoloRecordDummyGenerator {

    private static final int USER_COUNT = 10_000;
    private static final int MAX_RECORDS_PER_USER = 400; // 유저당 1~400건 → 평균 ~200건, 총 약 200만 건
    private static final int BATCH_SIZE = 5_000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void generate() {
        wipe();
        List<Long> userIds = insertDummyUsers();
        insertDummyRecords(userIds);
        jdbcTemplate.execute("ANALYZE TABLE solo_record");
        System.out.println("완료 — ANALYZE TABLE로 인덱스 통계 갱신됨");
    }

    private void wipe() {
        Long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM solo_record", Long.class);
        jdbcTemplate.execute("TRUNCATE TABLE solo_record");
        // 재실행 대비: 이전에 만든 더미 유저도 정리 (실계정 email과 겹치지 않는 패턴)
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'dummy%@test.com'");
        System.out.printf("기존 solo_record %d건 삭제%n", before);
    }

    private List<Long> insertDummyUsers() {
        long start = System.currentTimeMillis();
        String sql = "INSERT INTO users "
                + "(provider, provider_id, email, password, nickname, role, version, created_at, updated_at) "
                + "VALUES ('LOCAL', NULL, ?, NULL, ?, 'USER', 0, NOW(), NOW())";

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= USER_COUNT; i++) {
            batch.add(new Object[]{"dummy" + i + "@test.com", "더미" + i});
            if (batch.size() == BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }

        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM users WHERE email LIKE 'dummy%@test.com'", Long.class);
        System.out.printf("더미 유저 %d명 삽입 (%.1f초)%n", ids.size(), (System.currentTimeMillis() - start) / 1000.0);
        return ids;
    }

    private void insertDummyRecords(List<Long> userIds) {
        long start = System.currentTimeMillis();
        String sql = "INSERT INTO solo_record "
                + "(user_id, score, cleared_count, play_time_seconds, board_seed, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        ThreadLocalRandom random = ThreadLocalRandom.current();
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        long total = 0;

        for (Long userId : userIds) {
            int recordCount = random.nextInt(1, MAX_RECORDS_PER_USER + 1);
            for (int i = 0; i < recordCount; i++) {
                int clearedCount = random.nextInt(0, 51);
                int score = Math.min(170, clearedCount * random.nextInt(2, 5)); // 보드 사과 170개가 상한
                int playTimeSeconds = random.nextInt(30, 121);

                Timestamp createdAt = Timestamp.valueOf(now.minusSeconds(random.nextLong(0, 90L * 24 * 3600)));

                batch.add(new Object[]{userId, score, clearedCount, playTimeSeconds,
                        String.valueOf(random.nextLong()), createdAt, createdAt});
                total++;

                if (batch.size() == BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                    if (total % 100_000 < BATCH_SIZE) {
                        System.out.printf("solo_record %,d건... (%.1f초)%n", total, (System.currentTimeMillis() - start) / 1000.0);
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }
        System.out.printf("solo_record 총 %,d건 삽입 (%.1f초)%n", total, (System.currentTimeMillis() - start) / 1000.0);
    }
}
