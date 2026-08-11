package com.apple.game.domain.solo.repository;

import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.user.entity.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    // 메서드 명이 너무 길어져 직관적이지 못함
    // List<SoloRecord> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long cursor, Pageable pageable);
}
