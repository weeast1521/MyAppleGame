package com.apple.game.domain.user.controller;

import com.apple.game.domain.user.dto.req.UserReqDTO;
import com.apple.game.domain.user.dto.res.UserResDTO;
import com.apple.game.domain.user.service.UserService;
import com.apple.game.global.apiPayload.CustomResponse;
import com.apple.game.global.apiPayload.code.GeneralSuccessCode;
import com.apple.game.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사용자", description = "내 정보 조회 / 수정 API")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "내 정보 조회",
            description = "AccessToken으로 인증된 사용자 본인의 정보(userId, email, nickname, provider)를 조회한다.")
    @GetMapping("/me")
    public ResponseEntity<CustomResponse<UserResDTO.UserInfo>> myInfo(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        UserResDTO.UserInfo result = userService.myInfo(userDetails.getUserId());

        CustomResponse<UserResDTO.UserInfo> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "닉네임 변경",
            description = "본인의 닉네임을 변경한다. 2~12자, 중복 시 USER409를 반환한다.")
    @PatchMapping("/me/nickname")
    public ResponseEntity<CustomResponse<UserResDTO.UserInfo>> changeNickname(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UserReqDTO.ChangeNickname nextNickname
    ) {
        UserResDTO.UserInfo result = userService.changeNickname(userDetails.getUserId(), nextNickname);

        CustomResponse<UserResDTO.UserInfo> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
