package com.apple.game.domain.match.repository;

import com.apple.game.domain.match.entity.GameMatch;
import com.apple.game.domain.match.entity.MatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GameMatchRepository extends JpaRepository<GameMatch, Long> {

    // 방의 진행 중인 판 — 방당 PLAYING은 최대 1개지만, 과거 잔재(재시작으로 타이머를 잃은 판)가
    // 있을 수 있어 최신 것만 집는다
    Optional<GameMatch> findTopByRoomCodeAndStatusOrderByIdDesc(String roomCode, MatchStatus status);
}