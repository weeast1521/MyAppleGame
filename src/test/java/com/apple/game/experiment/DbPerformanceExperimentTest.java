package com.apple.game.experiment;

import com.apple.game.domain.match.dto.res.MatchResDTO;
import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.entity.MatchPlayer;
import com.apple.game.domain.match.entity.MatchResult;
import com.apple.game.domain.match.repository.GameMatchRepository;
import com.apple.game.domain.match.repository.MatchPlayerRepository;
import com.apple.game.domain.match.service.MatchQueryService;
import com.apple.game.domain.ranking.dto.res.RankingResDTO;
import com.apple.game.domain.ranking.repository.RankingRedisRepository;
import com.apple.game.domain.ranking.service.RankingService;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.security.jwt.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step 14 — DB 성능 실습 (수동 실행 전용).
 *
 * 실행: DB_EXPERIMENT=true ./gradlew test --tests DbPerformanceExperimentTest
 * 전제: 로컬 MySQL(3307)에 더미 데이터(SOLO_DUMMY 생성기: 유저 1만 · 기록 200만)가 들어 있을 것.
 * 결과: build/reports/db-experiment.md (그대로 docs/db_performance.md의 근거가 된다)
 *
 * 각 실험은 "가설 → 측정 → 실행계획"을 한 파일에 남긴다. 숫자는 이 맥의 로컬 Docker MySQL 기준이라
 * 절대치보다 before/after의 상대 비교가 목적이다.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true", // 쿼리 수 세기 (N+1 · 인증 필터)
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.orm.jdbc.bind=off",
        "logging.level.org.hibernate.stat=off"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "DB_EXPERIMENT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbPerformanceExperimentTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @PersistenceContext EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider jwt;
    @Autowired StringRedisTemplate redis;
    @Autowired RankingService rankingService;
    @Autowired MatchQueryService matchQueryService;
    @Autowired UserRepository userRepository;
    @Autowired SoloRecordRepository soloRecordRepository;
    @Autowired GameMatchRepository gameMatchRepository;
    @Autowired MatchPlayerRepository matchPlayerRepository;

    private final StringBuilder report = new StringBuilder("# DB 성능 실험 결과 (자동 생성)\n\n");

    @AfterAll
    void writeReport() throws IOException {
        Path out = Path.of("build/reports/db-experiment.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString(), StandardCharsets.UTF_8);
        System.out.println("=== 실험 리포트: " + out.toAbsolutePath());
    }

    // ---------- helpers ----------

    private void h(String title) { report.append("\n## ").append(title).append("\n\n"); }
    private void p(String line) { report.append(line).append("\n"); }
    private void code(String s) { report.append("```\n").append(s).append("\n```\n"); }

    /** EXPLAIN ANALYZE 원문 (MySQL 8 TREE 포맷) */
    private String explain(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN ANALYZE " + sql, args);
        StringBuilder sb = new StringBuilder();
        rows.forEach(r -> r.values().forEach(v -> sb.append(v).append('\n')));
        return sb.toString().trim();
    }

    /** 워밍업 1회 후 n회 실행의 중앙값(ms) — 버퍼 풀 워밍업 효과를 배제하고 안정 상태를 잰다 */
    private double medianMs(int n, Supplier<?> body) {
        body.get();
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            body.get();
            t[i] = System.nanoTime() - s;
        }
        Arrays.sort(t);
        return t[n / 2] / 1_000_000.0;
    }

    private Statistics stats() { return emf.unwrap(SessionFactory.class).getStatistics(); }

    private long dataVolume() { return jdbc.queryForObject("SELECT COUNT(*) FROM solo_record", Long.class); }

    // ---------- E1. offset vs 커서 ----------

    @Test
    @DisplayName("E1. offset 페이지네이션 vs 커서 — 깊은 페이지에서의 차이")
    void e1_offsetVsCursor() {
        h("E1. offset vs 커서 페이지네이션 (solo_record " + dataVolume() + "건)");
        p("가설: offset은 건너뛸 행을 전부 읽고 버리므로 깊어질수록 선형으로 느려지고, 커서(WHERE id < ?)는 어디서나 일정하다.\n");

        String offsetSql = "SELECT id, score, play_time_seconds, created_at FROM solo_record ORDER BY id DESC LIMIT 20 OFFSET ?";
        String cursorSql = "SELECT id, score, play_time_seconds, created_at FROM solo_record WHERE id < ? ORDER BY id DESC LIMIT 20";

        p("| offset | offset 방식 (ms) | 같은 위치 커서 방식 (ms) |");
        p("|---|---|---|");
        for (int off : new int[]{0, 10_000, 100_000, 500_000, 1_000_000, 1_500_000}) {
            long cursorId = jdbc.queryForObject("SELECT id FROM solo_record ORDER BY id DESC LIMIT 1 OFFSET ?", Long.class, off) + 1;
            double a = medianMs(5, () -> jdbc.queryForList(offsetSql, off));
            double b = medianMs(5, () -> jdbc.queryForList(cursorSql, cursorId));
            p(String.format("| %,d | %.2f | %.2f |", off, a, b));
        }
        p("\nEXPLAIN ANALYZE — offset 1,000,000:");
        code(explain(offsetSql, 1_000_000));
        long cid = jdbc.queryForObject("SELECT id FROM solo_record ORDER BY id DESC LIMIT 1 OFFSET 1000000", Long.class) + 1;
        p("EXPLAIN ANALYZE — 같은 위치 커서:");
        code(explain(cursorSql, cid));

        // 실제 API 형태: 특정 유저의 내 기록 (user_id 필터 + id 커서, idx_solo_record_user_id)
        Long heavy = jdbc.queryForObject("SELECT user_id FROM solo_record GROUP BY user_id ORDER BY COUNT(*) DESC LIMIT 1", Long.class);
        int cnt = jdbc.queryForObject("SELECT COUNT(*) FROM solo_record WHERE user_id = ?", Integer.class, heavy);
        p(String.format("\n유저 단위(기록 %d건인 유저 %d): 마지막 페이지 근처", cnt, heavy));
        String uOff = "SELECT id, score FROM solo_record WHERE user_id = ? ORDER BY id DESC LIMIT 20 OFFSET ?";
        String uCur = "SELECT id, score FROM solo_record WHERE user_id = ? AND id < ? ORDER BY id DESC LIMIT 20";
        long uc = jdbc.queryForObject("SELECT id FROM solo_record WHERE user_id = ? ORDER BY id DESC LIMIT 1 OFFSET ?", Long.class, heavy, cnt - 25) + 1;
        p(String.format("- offset %d: %.2f ms / 커서: %.2f ms", cnt - 25,
                medianMs(5, () -> jdbc.queryForList(uOff, heavy, cnt - 25)),
                medianMs(5, () -> jdbc.queryForList(uCur, heavy, uc))));
        p("  → 유저당 수백 건 규모에선 차이가 작다. 커서의 가치는 '전체' 같은 큰 집합에서 드러난다.");
    }

    // ---------- E2. 랭킹 DB 집계 vs Redis + 인덱스 전/후 ----------

    @Test
    @DisplayName("E2. 랭킹 — DB GROUP BY 집계 vs Redis ZSet, 그리고 (user_id, score) 인덱스 전/후")
    void e2_rankingDbVsRedisAndIndex() {
        h("E2. 랭킹 조회 — DB 집계 vs Redis ZSet (solo_record " + dataVolume() + "건, 유저 "
                + jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM solo_record", Long.class) + "명)");
        p("가설: 전체 랭킹은 '유저별 최고점' GROUP BY라 200만 행을 훑어야 하고(수백 ms), Redis ZSet은 정렬이 끝나 있어 ZREVRANGE 100건은 ms 미만이다.\n");

        String allTime = "SELECT u.id, u.nickname, MAX(r.score) AS best FROM solo_record r JOIN users u ON u.id = r.user_id "
                + "GROUP BY u.id, u.nickname ORDER BY best DESC";
        p("### 2-1. 전체 랭킹 DB 집계 (서비스가 쓰는 쿼리와 동일 형태, unpaged)");
        p(String.format("- 소요: %.1f ms (중앙값)", medianMs(3, () -> jdbc.queryForList(allTime))));
        code(explain(allTime));

        p("### 2-2. 서비스 경로 비교 (RankingService.getRanking)");
        String key = "ranking:solo:alltime";
        redis.delete(List.of(key, RankingRedisRepository.warmedKey(key)));
        long s = System.nanoTime();
        RankingResDTO.RankingPage dbPage = rankingService.getRanking(null, "alltime", 0, 100);
        double dbMs = (System.nanoTime() - s) / 1_000_000.0;
        double redisMs = medianMs(20, () -> rankingService.getRanking(null, "alltime", 0, 100));
        p(String.format("- 캐시 미스(DB 집계 + 1만 명 ZADD warm-up): %.1f ms, source=%s", dbMs, dbPage.source()));
        p(String.format("- 캐시 히트(ZREVRANGE 100 + 닉네임 IN 쿼리 1회): %.2f ms (중앙값, 20회)", redisMs));
        p(String.format("- 비율: 약 %.0f배", dbMs / redisMs));

        p("### 2-3. 인덱스 실험 — (user_id, score) 복합 인덱스로 GROUP BY MAX가 loose index scan이 되는가");
        p("가설: user_id로 그룹핑하고 score의 MAX만 필요하면, (user_id, score) 인덱스에서 각 user_id의 마지막 항목만 읽으면 된다(Loose Index Scan). 2M 행 전체 스캔 → 1만 번의 인덱스 점프.\n");
        String groupOnly = "SELECT user_id, MAX(score) AS best FROM solo_record GROUP BY user_id ORDER BY best DESC LIMIT 100";
        p(String.format("- 인덱스 없음: %.1f ms", medianMs(3, () -> jdbc.queryForList(groupOnly))));
        code(explain(groupOnly));
        jdbc.execute("CREATE INDEX idx_exp_user_score ON solo_record (user_id, score)");
        try {
            p(String.format("- (user_id, score) 인덱스 후: %.1f ms", medianMs(3, () -> jdbc.queryForList(groupOnly))));
            code(explain(groupOnly));
            p(String.format("- 서비스 쿼리(users JOIN 포함) 인덱스 후: %.1f ms", medianMs(3, () -> jdbc.queryForList(allTime))));
            code(explain(allTime));

            p("### 2-3b. 쿼리 재작성 — 집계를 먼저(파생 테이블) 하고 그 결과(1만 행)에만 users를 조인");
            p("관찰: 인덱스가 있어도 서비스 쿼리는 users를 바깥으로 두고 유저마다 solo_record를 조인(2M 행 생성)한 뒤 집계한다. 조인 순서를 강제하면 skip scan 결과 1만 행에 PK 조회 1만 번만 남는다.\n");
            String rewritten = "SELECT u.id, u.nickname, t.best FROM (SELECT user_id, MAX(score) AS best FROM solo_record GROUP BY user_id) t "
                    + "JOIN users u ON u.id = t.user_id ORDER BY t.best DESC";
            p(String.format("- 재작성 + 인덱스: %.1f ms", medianMs(3, () -> jdbc.queryForList(rewritten))));
            code(explain(rewritten));
        } finally {
            jdbc.execute("DROP INDEX idx_exp_user_score ON solo_record");
        }
        String rewrittenNoIdx = "SELECT u.id, u.nickname, t.best FROM (SELECT user_id, MAX(score) AS best FROM solo_record GROUP BY user_id) t "
                + "JOIN users u ON u.id = t.user_id ORDER BY t.best DESC";
        p(String.format("- 재작성만(인덱스 없음): %.1f ms — 인덱스와 재작성은 각각 다른 병목을 없앤다", medianMs(3, () -> jdbc.queryForList(rewrittenNoIdx))));

        p("### 2-4. 주간 랭킹 — created_at 범위 필터 + GROUP BY");
        String weekly = "SELECT user_id, MAX(score) AS best FROM solo_record WHERE created_at >= ? GROUP BY user_id ORDER BY best DESC";
        java.sql.Timestamp from = jdbc.queryForObject("SELECT DATE_SUB(MAX(created_at), INTERVAL 7 DAY) FROM solo_record", java.sql.Timestamp.class);
        long weekRows = jdbc.queryForObject("SELECT COUNT(*) FROM solo_record WHERE created_at >= ?", Long.class, from);
        p(String.format("- 최근 7일 범위 행 수: %,d / %,d", weekRows, dataVolume()));
        p(String.format("- 인덱스 없음: %.1f ms", medianMs(3, () -> jdbc.queryForList(weekly, from))));
        code(explain(weekly, from));
        jdbc.execute("CREATE INDEX idx_exp_created_user_score ON solo_record (created_at, user_id, score)");
        try {
            p(String.format("- (created_at, user_id, score) 인덱스 후: %.1f ms", medianMs(3, () -> jdbc.queryForList(weekly, from))));
            code(explain(weekly, from));
        } finally {
            jdbc.execute("DROP INDEX idx_exp_created_user_score ON solo_record");
        }
    }

    @Test
    @DisplayName("E2b. 인덱스·재작성 적용 후 서비스 경로 (캐시 미스 전체 소요)")
    void e2b_serviceAfterIndexAndRewrite() {
        h("E2b. 적용 후 — RankingService 캐시 미스 경로 (V3 인덱스 + 파생 테이블 네이티브 쿼리)");
        String key = "ranking:solo:alltime";
        double[] runs = new double[3];
        for (int i = 0; i < 3; i++) {
            redis.delete(List.of(key, RankingRedisRepository.warmedKey(key)));
            long s = System.nanoTime();
            rankingService.getRanking(null, "alltime", 0, 100);
            runs[i] = (System.nanoTime() - s) / 1_000_000.0;
        }
        Arrays.sort(runs);
        p(String.format("- 캐시 미스(DB 집계 + 1만 명 ZADD warm-up): %.1f ms (중앙값 3회) — 적용 전 1,829 ms", runs[1]));
        String weeklyKey = "ranking:solo:weekly:" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).get(java.time.temporal.WeekFields.ISO.weekBasedYear())
                + String.format("%02d", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()));
        for (int i = 0; i < 3; i++) {
            redis.delete(List.of(weeklyKey, RankingRedisRepository.warmedKey(weeklyKey)));
            long s = System.nanoTime();
            rankingService.getRanking(null, "weekly", 0, 100);
            runs[i] = (System.nanoTime() - s) / 1_000_000.0;
        }
        Arrays.sort(runs);
        p(String.format("- 주간 캐시 미스: %.1f ms (중앙값 3회)", runs[1]));
    }

    // ---------- E3. N+1 ----------

    @Test
    @DisplayName("E3. 대전 전적 N+1 재현 — 쿼리 수를 Hibernate 통계로 센다")
    void e3_nPlusOne() {
        h("E3. 대전 전적 N+1 — 쿼리 수 비교");
        p("가설: fetch join 없이 행마다 판·상대를 지연 로딩하면 쿼리 수가 페이지 크기에 비례(1 + N + N)하고, 현재 구현(fetch join + IN 묶음 + 집계)은 3개로 고정이다.\n");

        String tag = UUID.randomUUID().toString().substring(0, 6);
        User me = userRepository.save(User.createLocalUser("np-me-" + tag + "@t.com", "pw", "NP나" + tag.substring(0, 3)));
        User opp = userRepository.save(User.createLocalUser("np-op-" + tag + "@t.com", "pw", "NP상" + tag.substring(0, 3)));
        List<Long> matchIds = new ArrayList<>();
        int N = 20;
        for (int i = 0; i < N; i++) {
            GameMatch m = gameMatchRepository.save(GameMatch.start("NPX" + i, "seed"));
            m.finish();
            gameMatchRepository.save(m);
            matchPlayerRepository.save(MatchPlayer.of(m, me, 30, MatchResult.WIN));
            matchPlayerRepository.save(MatchPlayer.of(m, opp, 20, MatchResult.LOSE));
            matchIds.add(m.getId());
        }
        try {
            Statistics st = stats();
            st.setStatisticsEnabled(true);

            // (A) 순진한 구현: fetch join 없이 페이지 조회 → 행마다 match 접근 + 상대 조회
            st.clear();
            tx.execute(status -> {
                List<MatchPlayer> mine = em.createQuery(
                                "SELECT mp FROM MatchPlayer mp WHERE mp.user.id = :uid ORDER BY mp.id DESC", MatchPlayer.class)
                        .setParameter("uid", me.getId()).setMaxResults(N).getResultList();
                for (MatchPlayer mp : mine) {
                    mp.getMatch().getFinishedAt();                       // 지연 로딩 → SELECT game_match
                    em.createQuery("SELECT o FROM MatchPlayer o WHERE o.match.id = :mid AND o.user.id <> :uid", MatchPlayer.class)
                            .setParameter("mid", mp.getMatch().getId()).setParameter("uid", me.getId())
                            .getSingleResult().getUser().getNickname();   // 상대 조회 + 상대 user 지연 로딩
                }
                return null;
            });
            long naive = st.getPrepareStatementCount();

            // (B) 현재 구현
            st.clear();
            MatchResDTO.MyMatches page = matchQueryService.getMyMatches(me.getId(), null, N);
            long current = st.getPrepareStatementCount();

            p(String.format("| 방식 | 페이지 크기 | SQL 실행 수 |\n|---|---|---|\n| 순진한 구현 (지연 로딩) | %d | %d |\n| 현재 구현 (fetch join + IN + GROUP BY) | %d | %d |",
                    N, naive, page.matches().size(), current));
            p("\n순진한 구현의 내역: 페이지 1 + 판(game_match) N + 상대(match_player) N + 상대 유저(users) N 근처. 현재 구현은 페이지 크기와 무관하게 3.");
        } finally {
            matchPlayerRepository.deleteAll(matchPlayerRepository.findAll().stream()
                    .filter(mp -> matchIds.contains(mp.getMatch().getId())).toList());
            gameMatchRepository.deleteAllById(matchIds);
            userRepository.deleteAll(List.of(me, opp));
        }
    }

    // ---------- E4. 인증 필터 ----------

    @Test
    @DisplayName("E4. 인증 필터 — 요청당 SELECT 1번의 비중")
    void e4_authFilterQueryCount() throws Exception {
        h("E4. 인증 필터의 요청당 SELECT 1번");
        p("현재 구조: 토큰에 userId만 있어 JwtAuthenticationFilter가 매 요청 users를 findById로 읽어 role을 확인한다(Step 3의 A안). 이 SELECT가 API 1회 호출의 쿼리 중 몇 개인지 센다.\n");

        String tag = UUID.randomUUID().toString().substring(0, 6);
        User u = userRepository.save(User.createLocalUser("auth-" + tag + "@t.com", "pw", "인증" + tag.substring(0, 3)));
        try {
            String token = jwt.createAccessToken(u.getId(), u.getRole());
            Statistics st = stats();
            st.setStatisticsEnabled(true);

            // 랭킹은 캐시 히트 상태로 (warm-up 노이즈 제거)
            rankingService.getRanking(null, "alltime", 0, 20);

            st.clear();
            mvc.perform(get("/api/rankings/solo?period=alltime&size=20")).andExpect(status().isOk());
            long anon = st.getPrepareStatementCount();

            st.clear();
            mvc.perform(get("/api/rankings/solo?period=alltime&size=20").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            long authed = st.getPrepareStatementCount();

            st.clear();
            mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            long me = st.getPrepareStatementCount();

            p("| 요청 | SQL 실행 수 | 그중 인증 필터 |\n|---|---|---|");
            p(String.format("| GET /api/rankings/solo (비로그인) | %d | 0 |", anon));
            p(String.format("| GET /api/rankings/solo (로그인) | %d | %d |", authed, authed - anon));
            p(String.format("| GET /api/users/me | %d | %d |", me, me - 1));
            p("\n인증 필터의 SELECT는 '모든 인증 요청에 무조건 붙는 1번'이다. 단건은 ~1ms지만 요청 수에 비례해 커넥션 풀을 점유한다 — 부하에서의 영향은 k6 실험(docs)에서 본다.");
            double one = medianMs(20, () -> userRepository.findById(u.getId()));
            p(String.format("- users PK 조회 단건 소요: %.3f ms (중앙값, 20회 — 애플리케이션→MySQL 왕복 포함)", one));
        } finally {
            userRepository.delete(u);
        }
    }

    // ---------- E5. batch insert ----------

    @Test
    @DisplayName("E5. 대량 삽입 — JPA saveAll vs JdbcTemplate.batchUpdate(rewriteBatchedStatements)")
    void e5_batchInsert() {
        h("E5. 대량 삽입 10,000건 — JPA saveAll vs JdbcTemplate batchUpdate");
        p("가설: IDENTITY 전략의 JPA는 INSERT마다 왕복(배치 불가)하고, JDBC batch + rewriteBatchedStatements=true는 다중 VALUES 한 문장으로 묶어 왕복을 수십 배 줄인다.\n");
        String tag = UUID.randomUUID().toString().substring(0, 6);
        User u = userRepository.save(User.createLocalUser("bat-" + tag + "@t.com", "pw", "배치" + tag.substring(0, 3)));
        int n = 10_000;
        try {
            long s1 = System.nanoTime();
            List<SoloRecord> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(SoloRecord.create(u, i % 1000, 5, 60, "s"));
            soloRecordRepository.saveAll(list);
            double jpaMs = (System.nanoTime() - s1) / 1_000_000.0;
            jdbc.update("DELETE FROM solo_record WHERE user_id = ?", u.getId());

            long s2 = System.nanoTime();
            List<Object[]> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) batch.add(new Object[]{u.getId(), i % 1000, 5, 60, "s"});
            jdbc.batchUpdate("INSERT INTO solo_record (user_id, score, cleared_count, play_time_seconds, board_seed, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NOW(6), NOW(6))", batch);
            double jdbcMs = (System.nanoTime() - s2) / 1_000_000.0;

            p(String.format("| 방식 | 소요 |\n|---|---|\n| JPA saveAll (IDENTITY, 건별 INSERT) | %.0f ms |\n| JdbcTemplate.batchUpdate + rewriteBatchedStatements | %.0f ms |\n| 비율 | %.1f배 |", jpaMs, jdbcMs, jpaMs / jdbcMs));
            p("\n더미 생성기(SoloRecordDummyGenerator)가 JdbcTemplate batch를 쓰는 이유. rewriteBatchedStatements가 없으면 batch도 건별 전송이 된다(CI에서 25분 걸렸던 원인, #26).");
        } finally {
            jdbc.update("DELETE FROM solo_record WHERE user_id = ?", u.getId());
            userRepository.delete(u);
        }
    }
}
