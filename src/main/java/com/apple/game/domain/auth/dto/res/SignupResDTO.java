package com.apple.game.domain.auth.dto.res;

import com.apple.game.domain.user.entity.User;

public class SignupResDTO {

    public record Signup(
            Long userId,
            String loginId,
            String nickname
    ) {
        public static Signup from(User user) {
            return new Signup(user.getId(), user.getLoginId(), user.getNickname());
        }
    }
}
