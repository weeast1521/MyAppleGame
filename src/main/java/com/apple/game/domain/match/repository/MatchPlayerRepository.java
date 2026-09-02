package com.apple.game.domain.match.repository;

import com.apple.game.domain.match.entity.MatchPlayer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchPlayerRepository extends JpaRepository<MatchPlayer, Long> {
}
