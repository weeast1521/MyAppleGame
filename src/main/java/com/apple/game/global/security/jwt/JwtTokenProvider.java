package com.apple.game.global.security.jwt;


import com.apple.game.domain.user.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    // 우리 서버가 발급한 토큰인지 서명(진위를 보장하는 KEY)
    private final SecretKey secretKey;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;

    public JwtTokenProvider(JwtProperties properties) {
        // base64로 인코딩이 된 문자열(jwt.secret)을 다시 byte로 decode 해서 HMAC-SHA 서명에 사용 가능한 SecretKey 객체로 감싼다)
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.accessTokenExpiration = properties.accessTokenExpiration();
        this.refreshTokenExpiration = properties.refreshTokenExpiration();
    }

    public static final String ROLE_CLAIM = "role";

    // ----- 발급 -----
    // 액세스 토큰에 role을 싣는다(Step 14 B안). 인증 필터가 매 요청 users를 읽지 않아도 권한을 알 수 있다.
    // 트레이드오프: 권한 변경·차단이 이 토큰의 만료(30분)까지 반영되지 않는다 — 재발급(refresh)은 DB를 다시 읽으므로 그때 반영.
    public String createAccessToken(Long userId, Role role) {
        return createToken(userId, role, accessTokenExpiration);
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, null, refreshTokenExpiration);
    }

    private String createToken(Long userId, Role role, Duration ttl) {
        Instant now = Instant.now();

        var builder = Jwts.builder()
                .subject(String.valueOf(userId)) // sub = userId
                .issuedAt(Date.from(now)) // iat
                .expiration(Date.from(now.plus(ttl))); // exp
        if (role != null) builder.claim(ROLE_CLAIM, role.name());
        return builder.signWith(secretKey).compact();
    }

    // ----- 검증 -----
    /**
     * 유효하면 true. 예외를 밖으로 던지지 않는다.
     * — 필터는 "토큰이 없거나 잘못됨"을 익명 통과로 처리해야 하기 때문(랭킹 API가 비로그인도 허용).
     */
    // jwt = header(어떤 알고리즘으로 서명?) + payload(실 데이터. sub, iat, exp 등 클레임들) + signature(secretKey로 header+payload를 서명한 값)
    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("만료된 토큰입니다.");
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("유효하지 않은 토큰입니다: {}", e.getMessage());
        }
        return false;
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    // role 클레임. 이 배포 이전에 발급된 토큰(클레임 없음)이면 null — 필터가 DB 조회로 폴백한다(전환기 호환)
    public Role getRole(String token) {
        String v = parseClaims(token).get(ROLE_CLAIM, String.class);
        return v == null ? null : Role.valueOf(v);
    }

    public Instant getExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }


}
