package com.apple.game.global.security.jwt;

import com.apple.game.domain.user.entity.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 계약:
 *  - access/refresh는 같은 키로 서명되지만 typ 클레임으로 종류가 구분된다
 *  - validateAccessToken은 typ=ACCESS만, validateRefreshToken은 typ=REFRESH만 통과
 *  - 전환기: typ이 없는 옛 토큰은 refresh 검증만 통과한다(재발급의 DB 조회가 최종 확인)
 *  - 위조·만료 토큰은 둘 다 false (예외를 던지지 않는다)
 */
class JwtTokenProviderTest {

    static final byte[] KEY_BYTES = "test-secret-key-must-be-at-least-32-bytes!".getBytes(StandardCharsets.UTF_8);
    static final String SECRET = Base64.getEncoder().encodeToString(KEY_BYTES);

    static JwtTokenProvider provider(Duration accessTtl, Duration refreshTtl) {
        return new JwtTokenProvider(new JwtProperties(SECRET, accessTtl, refreshTtl));
    }

    static JwtTokenProvider provider() {
        return provider(Duration.ofMinutes(30), Duration.ofDays(14));
    }

    /** typ 도입 전 형식의 토큰 — sub/iat/exp(+role)만 있다 */
    static String legacyToken(Long userId, Role role) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMinutes(30))));
        if (role != null) builder.claim(JwtTokenProvider.ROLE_CLAIM, role.name());
        return builder.signWith(Keys.hmacShaKeyFor(KEY_BYTES)).compact();
    }

    @Nested
    @DisplayName("종류 구분")
    class TypeCheck {

        @Test
        @DisplayName("access는 access 검증만 통과한다")
        void accessPassesOnlyAccessValidation() {
            JwtTokenProvider p = provider();
            String access = p.createAccessToken(1L, Role.USER);

            assertThat(p.validateAccessToken(access)).isTrue();
            assertThat(p.validateRefreshToken(access)).isFalse();
        }

        @Test
        @DisplayName("refresh는 access 검증을 통과하지 못한다 — refresh가 API 인증에 쓰이던 문제의 회귀 방지")
        void refreshFailsAccessValidation() {
            JwtTokenProvider p = provider();
            String refresh = p.createRefreshToken(1L);

            assertThat(p.validateAccessToken(refresh)).isFalse();
            assertThat(p.validateRefreshToken(refresh)).isTrue();
        }

        @Test
        @DisplayName("typ 없는 옛 토큰은 access로 인정하지 않고, 전환기 동안 refresh 검증만 통과한다")
        void legacyTokenWithoutType() {
            JwtTokenProvider p = provider();

            assertThat(p.validateAccessToken(legacyToken(1L, Role.USER))).isFalse();
            assertThat(p.validateAccessToken(legacyToken(1L, null))).isFalse();
            assertThat(p.validateRefreshToken(legacyToken(1L, null))).isTrue();
        }

        @Test
        @DisplayName("access에는 role이 실리고 refresh에는 없다")
        void roleOnlyInAccess() {
            JwtTokenProvider p = provider();

            assertThat(p.getRole(p.createAccessToken(1L, Role.ADMIN))).isEqualTo(Role.ADMIN);
            assertThat(p.getRole(p.createRefreshToken(1L))).isNull();
        }
    }

    @Nested
    @DisplayName("서명·만료")
    class SignatureAndExpiry {

        @Test
        @DisplayName("다른 키로 서명된 토큰은 둘 다 false")
        void forgedSignature() {
            byte[] otherKey = "another-secret-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8);
            JwtTokenProvider forger = new JwtTokenProvider(new JwtProperties(
                    Base64.getEncoder().encodeToString(otherKey), Duration.ofMinutes(30), Duration.ofDays(14)));
            JwtTokenProvider p = provider();

            assertThat(p.validateAccessToken(forger.createAccessToken(1L, Role.USER))).isFalse();
            assertThat(p.validateRefreshToken(forger.createRefreshToken(1L))).isFalse();
        }

        @Test
        @DisplayName("만료된 토큰은 둘 다 false")
        void expired() {
            JwtTokenProvider p = provider(Duration.ofSeconds(-1), Duration.ofSeconds(-1));

            assertThat(p.validateAccessToken(p.createAccessToken(1L, Role.USER))).isFalse();
            assertThat(p.validateRefreshToken(p.createRefreshToken(1L))).isFalse();
        }

        @Test
        @DisplayName("형식이 깨진 문자열은 예외 없이 false")
        void malformed() {
            JwtTokenProvider p = provider();

            assertThat(p.validateAccessToken("not.a.jwt")).isFalse();
            assertThat(p.validateRefreshToken("")).isFalse();
        }
    }
}
