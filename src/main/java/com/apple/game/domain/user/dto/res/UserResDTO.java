package com.apple.game.domain.user.dto.res;

import com.apple.game.domain.user.entity.Provider;
import com.apple.game.domain.user.entity.User;

public class UserResDTO {

    public record UserInfo(
            Long userId,
            String email,
            String nickname,
            Provider provider
    ) {
        public static UserInfo from(User user) {
            return new UserInfo(user.getId(), user.getEmail(), user.getNickname(), user.getProvider());
        }
    }
}