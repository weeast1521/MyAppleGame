package com.apple.game.domain.auth.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginReqDTO {

    public record Login(
            @NotBlank(message = "이메일을 입력해주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 254, message = "이메일이 너무 깁니다.")
            String email,

            // 가입 상한(64)과 같다. 로그인은 BCrypt matches 를 돌리므로 가입과 같은 이유로 길이를 막는다(#58)
            @NotBlank(message = "비밀번호를 입력해주세요.")
            @Size(max = 64, message = "비밀번호는 64자 이하여야 합니다.")
            String password
    ) {}
}

