package com.apple.game.global.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "jwt") // 설정 파일에서 jwt로 시작하는 프로퍼티들을 이 필드에 자동으로 바딘딩 해줌
public record JwtProperties(

        @NotBlank(message = "jwt.secret 이 설정되지 않았습니다.")
        String secret,

        @NotNull(message = "jwt.access-token-expiration 이 설정되지 않았습니다.")
        Duration accessTokenExpiration,

        @NotNull(message = "jwt.refresh-token-expiration 이 설정되지 않았습니다.")
        Duration refreshTokenExpiration
) {
}
