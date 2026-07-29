package com.apple.game.domain.auth.controller;

import com.apple.game.domain.auth.dto.req.LoginReqDTO;
import com.apple.game.domain.auth.dto.req.ReissueReqDTO;
import com.apple.game.domain.auth.dto.req.SignupReqDTO;
import com.apple.game.domain.auth.dto.res.LoginResDTO;
import com.apple.game.domain.auth.dto.res.ReissueResDTO;
import com.apple.game.domain.auth.dto.res.SignupResDTO;
import com.apple.game.domain.auth.service.AuthService;
import com.apple.game.global.apiPayload.CustomResponse;
import com.apple.game.global.apiPayload.code.GeneralSuccessCode;
import com.apple.game.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입 / 로그인 / 토큰 재발급 / 로그아웃 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "일반 회원가입(Provider = LOCAL)", description = "EMAIL, PASSWORD, NICKNAME으로 가입한다.")
    @PostMapping("/signup")
    public ResponseEntity<CustomResponse<SignupResDTO.Signup>> signup(
            @Valid @RequestBody SignupReqDTO.Signup request) {
        SignupResDTO.Signup result = authService.signup(request);
        CustomResponse<SignupResDTO.Signup> response = CustomResponse.onSuccess(GeneralSuccessCode.CREATED, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "일반 로그인",
            description = "email/password로 로그인하고 accessToken·refreshToken을 발급한다. "
                    + "인증 실패 시 AUTH401_1, 소셜 가입 계정이면 AUTH409_1을 반환한다.")
    @PostMapping("/login")
    public ResponseEntity<CustomResponse<LoginResDTO.Login>> login(
            @Valid @RequestBody LoginReqDTO.Login request) {
        LoginResDTO.Login result = authService.login(request);

        CustomResponse<LoginResDTO.Login> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "토큰 재발급",
            description = "refreshToken으로 accessToken·refreshToken 새 쌍을 발급한다(회전). "
                    + "만료·폐기된 토큰이면 AUTH401_3을 반환하며 재로그인이 필요하다.")
    @PostMapping("/reissue")
    public ResponseEntity<CustomResponse<ReissueResDTO.Reissue>> reissue(
            @Valid @RequestBody ReissueReqDTO.Reissue request) {
        ReissueResDTO.Reissue result = authService.reissue(request);
        CustomResponse<ReissueResDTO.Reissue> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "로그아웃", description = "본인의 refreshToken을 폐기한다. 인증 필요.")
    @PostMapping("/logout")
    public ResponseEntity<CustomResponse<Void>> logout(@AuthenticationPrincipal CustomUserDetails userDetails){
        authService.logout(userDetails.getUserId());

        CustomResponse<Void> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, null);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
