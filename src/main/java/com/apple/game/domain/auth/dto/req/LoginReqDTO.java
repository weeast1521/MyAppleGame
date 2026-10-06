package com.apple.game.domain.auth.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginReqDTO {

    public record Login(
            // 가입과 달리 형식(LOGIN_ID_REGEX)을 검사하지 않는다 — 아이디 전환(#59, V4) 전에 가입한 계정은
            // 기존 이메일 문자열이 그대로 아이디라서, 형식을 강제하면 그들이 로그인할 수 없다. 길이만 막는다.
            @NotBlank(message = "아이디를 입력해주세요.")
            @Size(max = 254, message = "아이디가 너무 깁니다.")
            String loginId,

            // 가입 상한(64)과 같다. 로그인은 BCrypt matches 를 돌리므로 가입과 같은 이유로 길이를 막는다(#58)
            @NotBlank(message = "비밀번호를 입력해주세요.")
            @Size(max = 64, message = "비밀번호는 64자 이하여야 합니다.")
            String password
    ) {}
}
