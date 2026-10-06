package com.apple.game.domain.auth.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class SignupReqDTO {

    // 아이디 규칙. 이메일을 받지 않기로 한 뒤(#59 A안) 식별자는 사용자가 정하는 임의의 문자열이다.
    // 소문자·숫자·밑줄 4~20자 — 대소문자 혼동과 유사 문자(공백, 전각)를 애초에 막는다.
    public static final String LOGIN_ID_REGEX = "^[a-z0-9_]{4,20}$";

    public record Signup(
            @NotBlank(message = "아이디를 입력해주세요.")
            @Pattern(regexp = LOGIN_ID_REGEX, message = "아이디는 영문 소문자·숫자·밑줄(_) 4~20자여야 합니다.")
            String loginId,

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
