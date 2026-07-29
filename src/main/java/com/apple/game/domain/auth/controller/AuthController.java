package com.apple.game.domain.auth.controller;

import com.apple.game.domain.auth.dto.req.LoginReqDTO;
import com.apple.game.domain.auth.dto.req.SignupReqDTO;
import com.apple.game.domain.auth.dto.res.LoginResDTO;
import com.apple.game.domain.auth.dto.res.SignupResDTO;
import com.apple.game.domain.auth.service.AuthService;
import com.apple.game.global.apiPayload.CustomResponse;
import com.apple.game.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입 / 로그인 API")
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

    @Operation(summary = "일반 로그인", description = "기본 Local 로그인")
    @PostMapping("/login")
    public ResponseEntity<CustomResponse<LoginResDTO.Login>> login(
            @Valid @RequestBody LoginReqDTO.Login request) {
        LoginResDTO.Login result = authService.login(request);

        CustomResponse<LoginResDTO.Login> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
