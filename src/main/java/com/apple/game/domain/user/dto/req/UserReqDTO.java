package com.apple.game.domain.user.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserReqDTO {

    public record ChangeNickname(
            @NotBlank(message = "닉네임을 입력해주세요.")
            @Size(min = 2, max = 12, message = "닉네임은 2~15자여야 합니다.")
            String nickname
    ) {
    }
}
