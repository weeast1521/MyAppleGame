package com.apple.game.domain.auth.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SignupReqDTO {

    public record Signup(
            @NotBlank(message = "이메일을 입력해주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            String email,

            @NotBlank(message = "비밀번호를 입력해주세요.")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$",
                    message = "비밀번호는 8자 이상, 영문+숫자+특수문자를 포함해야 합니다."
            )
            String password,

            @NotBlank(message = "닉네임을 입력해주세요.")
            @Size(min = 2, max = 12, message = "닉네임은 2~15자여야 합니다.")
            String nickname
    ) {}
}
