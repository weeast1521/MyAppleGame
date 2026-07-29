package com.apple.game.domain.auth.dto.res;

import com.apple.game.domain.user.entity.User;

public class LoginResDTO {

    public record Login(
            String accessToken,
            String refreshToken,
            UserInfo user
    ) {
        public static Login of(String accessToken, String refreshToken, User user) {
            return new Login(accessToken, refreshToken, UserInfo.from(user));
        }
    }

    public record UserInfo(
            Long userId,
            String nickname,
            String provider
    ) {
        public static UserInfo from(User user) {
            return new UserInfo(user.getId(), user.getNickname(), user.getProvider().name());
        }
    }
}
