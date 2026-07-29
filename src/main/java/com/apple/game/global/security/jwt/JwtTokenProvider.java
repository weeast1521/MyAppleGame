package com.apple.game.global.security.jwt;


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

    // ----- 발급 -----
    public String createAccessToken(Long userId) {
        return createToken(userId, accessTokenExpiration);
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, refreshTokenExpiration);
    }

    private String createToken(Long userId, Duration ttl) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(String.valueOf(userId)) // sub = userId
                .issuedAt(Date.from(now)) // iat
                .expiration(Date.from(now.plus(ttl))) // exp
                .signWith(secretKey)
                .compact();
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
