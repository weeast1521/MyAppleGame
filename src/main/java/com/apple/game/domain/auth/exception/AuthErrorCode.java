package com.apple.game.domain.auth.exception;

import com.apple.game.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH401", "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH401_1", "이메일 또는 비밀번호가 일치하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_3", "만료되었거나 폐기된 리프레시 토큰입니다. 다시 로그인해주세요."),

    // 409
    EMAIL_CONFLICT(HttpStatus.CONFLICT, "AUTH409", "이미 가입한 email입니다."),
    SOCIAL_ACCOUNT(HttpStatus.CONFLICT, "AUTH409_1", "소셜 계정으로 가입된 이메일입니다. 해당 소셜 로그인을 이용해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}