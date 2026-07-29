package com.apple.game.domain.auth.dto.req;

import jakarta.validation.constraints.NotBlank;

public class ReissueReqDTO {

    public record Reissue(
            @NotBlank(message = "refreshToken이 필요합니다.")
            String refreshToken
    ) {}
}
