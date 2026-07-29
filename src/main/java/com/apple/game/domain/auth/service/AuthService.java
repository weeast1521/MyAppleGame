package com.apple.game.domain.auth.service;

import com.apple.game.domain.auth.dto.req.SignupReqDTO;
import com.apple.game.domain.auth.dto.res.SignupResDTO;
import com.apple.game.domain.auth.exception.AuthErrorCode;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public SignupResDTO.Signup signup(SignupReqDTO.Signup request) {
        // email, nickname 미리 중복 체크
        if (userRepository.existsByEmail(request.email())) {
            throw new CustomException(AuthErrorCode.EMAIL_CONFLICT);
        }

        if (userRepository.existsByNickname(request.nickname())) {
            throw new CustomException(UserErrorCode.NICKNAME_CONFLICT);
        }

        User user = User
                .createLocalUser(request.email(), passwordEncoder.encode(request.password()), request.nickname());

        return SignupResDTO.Signup.from(userRepository.save(user));
    }
}
