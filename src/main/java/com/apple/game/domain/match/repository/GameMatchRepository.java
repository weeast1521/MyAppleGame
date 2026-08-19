package com.apple.game.domain.match.repository;

import com.apple.game.domain.match.entity.GameMatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameMatchRepository extends JpaRepository<GameMatch, Long> {
}