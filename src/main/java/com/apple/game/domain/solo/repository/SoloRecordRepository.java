package com.apple.game.domain.solo.repository;

import com.apple.game.domain.solo.entity.SoloRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SoloRecordRepository extends JpaRepository<SoloRecord, Long> {

    Optional<SoloRecord> findTopByUserIdOrderByScoreDesc(Long userId);

    @Query("SELECT COUNT(DISTINCT r.user.id) FROM SoloRecord r WHERE r.score > :score")
    long countUsersWithScoreAbove(@Param("score") int score);
}
