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

    @Query("SELECT u.id AS userId, u.nickname AS nickname, MAX(r.score) AS bestScore "
            + "FROM SoloRecord r JOIN r.user u "
            + "GROUP BY u.id, u.nickname "
            + "ORDER BY max(r.score) DESC")
    List<RankingRow> findAllTimeRanking(Pageable pageable);

    @Query("select u.id AS userId, u.nickname AS nickname, MAX(r.score) AS bestScore "
            + "FROM SoloRecord r JOIN r.user u "
            + "WHERE r.createdAt >= :from "
            + "GROUP BY u.id, u.nickname "
            + "ORDER BY max(r.score) DESC")
    List<RankingRow> findWeeklyRanking(@Param("from") LocalDateTime from, Pageable pageable);

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
