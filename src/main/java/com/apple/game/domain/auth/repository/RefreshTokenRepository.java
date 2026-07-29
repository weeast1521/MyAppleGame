package com.apple.game.domain.auth.repository;

import com.apple.game.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    void deleteByUserId(Long userId);   // 로그인 시 기존 토큰 교체 + 로그아웃에서 사용
}
