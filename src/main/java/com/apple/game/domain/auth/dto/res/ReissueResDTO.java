package com.apple.game.domain.auth.dto.res;

public class ReissueResDTO {
    public record Reissue(String accessToken, String refreshToken) {}
}
