package com.apple.game.domain.match.repository;

import com.apple.game.domain.match.entity.MatchPlayer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MatchPlayerRepository extends JpaRepository<MatchPlayer, Long> {

    // 내 전적 페이지 — 커서(id 내림차순) + JOIN FETCH로 판 정보를 함께 로드해
    // 행마다 match를 지연 로딩하는 N+1을 막는다. (user_id, id) 복합 인덱스를 탄다.
    @Query("SELECT mp FROM MatchPlayer mp JOIN FETCH mp.match "
            + "WHERE mp.user.id = :userId AND mp.id < :cursor "
            + "ORDER BY mp.id DESC")
    Slice<MatchPlayer> findPageByUserId(@Param("userId") Long userId, @Param("cursor") Long cursor, Pageable pageable);

    // 같은 판들의 상대편 행 — 페이지의 matchId 묶음으로 한 번에 가져온다(행당 조회 대신 IN 1회).
    // 닉네임 표시용이므로 user까지 FETCH.
    @Query("SELECT mp FROM MatchPlayer mp JOIN FETCH mp.user "
            + "WHERE mp.match.id IN :matchIds AND mp.user.id <> :userId")
    List<MatchPlayer> findOpponents(@Param("matchIds") List<Long> matchIds, @Param("userId") Long userId);

    // 전적 요약 — 결과별 판 수를 DB에서 집계 (전체 행을 당겨와 세지 않는다)
    @Query("SELECT mp.result, COUNT(mp) FROM MatchPlayer mp WHERE mp.user.id = :userId GROUP BY mp.result")
    List<Object[]> countByResult(@Param("userId") Long userId);
}
