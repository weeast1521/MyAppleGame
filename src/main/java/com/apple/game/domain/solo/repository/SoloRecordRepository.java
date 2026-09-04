package com.apple.game.domain.solo.repository;

import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SoloRecordRepository extends JpaRepository<SoloRecord, Long> {

    Optional<SoloRecord> findTopByUserIdOrderByScoreDesc(Long userId);

    // r.user.id는 SoloRecord 안에 이미 userId가 존재하기에 join이 필요 없다. 대신 nickname 같은 값이 필요하면 join이 필
    @Query("SELECT COUNT(DISTINCT r.user.id) FROM SoloRecord r WHERE r.score > :score")
    long countUsersWithScoreAbove(@Param("score") int score);

    // cursor 페이지네이션 - id 내림차순으로 cursor 이전 기록을 조회
    // Slice가 내부적으로 size + 1 개를 조회해 hasNext를 판정
    @Query("SELECT r FROM SoloRecord r "
            + "WHERE r.user.id = :userId AND r.id < :cursor "
            + "ORDER BY r.id DESC")
    Slice<SoloRecord> findPageByUserId(@Param("userId") Long userId, @Param("cursor") Long cursor, Pageable pageable);

    // best/count/avg를 쿼리 한 번으로 — 기록이 0건이면 MAX/AVG는 null, COUNT는 0
    @Query("SELECT MAX(r.score) AS bestScore, COUNT(r) AS totalGames, AVG(r.score) AS averageScore "
            + "FROM SoloRecord r WHERE r.user.id = :userId")
    SummaryProjection aggregateByUserId(@Param("userId") Long userId);

    // 집계 결과를 받는 인터페이스 프로젝션 — getter 이름이 위 쿼리의 AS 별칭과 매칭된다
    interface SummaryProjection {
        Integer getBestScore();
        long getTotalGames();
        Double getAverageScore();
    }

    // ---------------- Ranking 조회 -------------------

    // 랭킹 집계 — warm-up용 전체 조회(캐시 미스 시에만 실행).
    // 네이티브 + 파생 테이블인 이유(docs/db_performance.md E2):
    //  JPQL "FROM SoloRecord r JOIN r.user u GROUP BY u.id"는 옵티마이저가 users를 바깥에 두고 유저마다
    //  solo_record를 조인해 200만 행을 만든 뒤 집계한다(인덱스가 있어도 587ms).
    //  집계를 먼저 하면 (user_id, score) 인덱스의 skip scan으로 1만 행만 나오고, 거기에 users PK 조회 1만 번 → 29ms.
    //  JPQL은 조인 순서를 강제할 수 없어 SQL로 내렸다. 별칭(userId·nickname·bestScore)이 RankingRow 프로젝션과 일치해야 한다.
    @Query(value = "SELECT u.id AS userId, u.nickname AS nickname, t.best AS bestScore "
            + "FROM (SELECT user_id, MAX(score) AS best FROM solo_record GROUP BY user_id) t "
            + "JOIN users u ON u.id = t.user_id "
            + "ORDER BY t.best DESC", nativeQuery = true)
    List<RankingRow> findAllTimeRanking();

    // 주간: created_at 범위를 파생 테이블 안에서 자르면 (created_at, user_id, score) 커버링 인덱스만 읽는다
    @Query(value = "SELECT u.id AS userId, u.nickname AS nickname, t.best AS bestScore "
            + "FROM (SELECT user_id, MAX(score) AS best FROM solo_record WHERE created_at >= :from GROUP BY user_id) t "
            + "JOIN users u ON u.id = t.user_id "
            + "ORDER BY t.best DESC", nativeQuery = true)
    List<RankingRow> findWeeklyRanking(@Param("from") LocalDateTime from);

    // 내 주간 최고점 — 기록 없으면 null
    @Query("SELECT MAX(r.score) AS bestScore "
            + "FROM SoloRecord r "
            + "WHERE r.user.id = :userId AND r.createdAt >= :from")
    Integer findMyWeeklyBestScore(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    // 주간 버전의 countUsersWithScoreAbove — 내 순위 계산용
    @Query("SELECT COUNT(DISTINCT r.user.id) FROM SoloRecord r "
            + "WHERE r.createdAt >= :from AND r.score > :score")
    long findMyWeeklyRanking(@Param("from") LocalDateTime from, @Param("score") int score);

    interface RankingRow {
        Long getUserId();
        String getNickname();
        Integer getBestScore();
    }
}
