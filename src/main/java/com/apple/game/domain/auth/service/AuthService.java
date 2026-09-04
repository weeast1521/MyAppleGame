package com.apple.game.domain.auth.service;

import com.apple.game.domain.auth.dto.req.LoginReqDTO;
import com.apple.game.domain.auth.dto.req.ReissueReqDTO;
import com.apple.game.domain.auth.dto.req.SignupReqDTO;
import com.apple.game.domain.auth.dto.res.LoginResDTO;
import com.apple.game.domain.auth.dto.res.ReissueResDTO;
import com.apple.game.domain.auth.dto.res.SignupResDTO;
import com.apple.game.domain.auth.entity.RefreshToken;
import com.apple.game.domain.auth.exception.AuthErrorCode;
import com.apple.game.domain.auth.repository.RefreshTokenRepository;
import com.apple.game.domain.user.entity.Provider;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.exception.UserErrorCode;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import com.apple.game.global.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

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

        // 위 exists 검사와 INSERT 사이에는 틈이 있다 — 같은 이메일로 동시에 가입하면 둘 다 검사를 통과하고
        // UNIQUE 제약이 한쪽을 막는다(Step 15 부하 테스트에서 100명 중 9명이 여기까지 도달).
        // 제약 위반은 버그가 아니라 "먼저 온 쪽이 이겼다"는 뜻이므로 선 조회 때와 같은 409로 답한다.
        // flush를 여기서 해야 예외가 커밋 시점이 아니라 이 try 안에서 터진다.
        try {
            return SignupResDTO.Signup.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("uk_users_nickname")) throw new CustomException(UserErrorCode.NICKNAME_CONFLICT);
            if (cause.contains("uk_users_email")) throw new CustomException(AuthErrorCode.EMAIL_CONFLICT);
            throw e; // 그 외 제약은 GlobalExceptionHandler가 COMMON409로
        }
    }

    @Transactional
    public LoginResDTO.Login login(LoginReqDTO.Login request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_CREDENTIALS));

        if (user.getProvider() != Provider.LOCAL) {
            throw new CustomException(AuthErrorCode.SOCIAL_ACCOUNT);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new CustomException(AuthErrorCode.INVALID_CREDENTIALS);
        }

        return issueTokens(user);
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    @Transactional
    public ReissueResDTO.Reissue reissue(ReissueReqDTO.Reissue request) {
        String refreshToken = request.refreshToken();

        // 1) 토큰 자체 검증 (서명 위조·만료)
        if (!jwtTokenProvider.validate(refreshToken)) throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);

        // 2) DB 존재 확인 — 없으면 이미 회전으로 폐기됐거나 로그아웃된 토큰
        RefreshToken saved = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 3) 회전(rotation): 쓴 토큰은 즉시 폐기 — 탈취된 토큰의 재사용을 막는다
        refreshTokenRepository.delete(saved);

        User user = userRepository.findById(saved.getUserId())
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 4) 새 쌍 발급 — issueTokens가 저장까지 담당
        LoginResDTO.Login tokens = issueTokens(user);

        return new ReissueResDTO.Reissue(tokens.accessToken(), tokens.refreshToken());
    }

    // 토큰 발급 + RefreshToken 저장 진입점
    private LoginResDTO.Login issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());

        // 1유저 1토큰 정책: 기존 토큰을 지우고 새로 저장 (다중 기기 로그인은 허용하지 않는다)
        refreshTokenRepository.deleteByUserId(user.getId());
        refreshTokenRepository.save(
                RefreshToken.of(user.getId(), refreshToken, jwtTokenProvider.getExpiration(refreshToken)));

        return LoginResDTO.Login.of(accessToken, refreshToken, user);
    }
}
