package com.apple.game.domain.user.service;

import com.apple.game.domain.user.dto.req.UserReqDTO;
import com.apple.game.domain.user.dto.res.UserResDTO;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserResDTO.UserInfo myInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));

        return UserResDTO.UserInfo.from(user);
    }

    @Transactional
    public UserResDTO.UserInfo changeNickname(Long userId, UserReqDTO.ChangeNickname nextNickname) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.NOT_FOUND));

        if (userRepository.existsByNickname(nextNickname.nickname())) {
            throw new CustomException(UserErrorCode.NICKNAME_CONFLICT);
        }

        user.changeNickname(nextNickname.nickname());

        return UserResDTO.UserInfo.from(user);
    }
}
