package com.apple.game.domain.auth.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SignupReqDTO {

    public record Signup(
            @NotBlank(message = "이메일을 입력해주세요.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 254, message = "이메일이 너무 깁니다.") // RFC 5321 상한. users.email 컬럼 길이와도 맞춘다
            String email,

            @NotBlank(message = "비밀번호를 입력해주세요.")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,}$",
                    message = "비밀번호는 8자 이상, 영문+숫자+특수문자를 포함해야 합니다."
            )
            // BCrypt 는 입력의 72바이트까지만 본다 — 그 뒤는 무시되므로 더 길게 받을 이유가 없고,
            // 상한이 없으면 요청 1건으로 수 MB 를 해시기에 밀어 넣을 수 있다(#58, 요청 1건의 비용 상한)
            @Size(max = 64, message = "비밀번호는 64자 이하여야 합니다.")
            String password,

            @NotBlank(message = "닉네임을 입력해주세요.")
            @Size(min = 2, max = 12, message = "닉네임은 2~12자여야 합니다.")
            String nickname
    ) {}
}
