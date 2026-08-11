package com.apple.game.domain.solo.controller;

import com.apple.game.domain.solo.dto.req.SoloReqDTO;
import com.apple.game.domain.solo.dto.res.SoloResDTO;
import com.apple.game.domain.solo.service.SoloGameService;
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

@Tag(name = "솔로 모드", description = "솔로 게임 시작 / 종료 API")
@RestController
@RequestMapping("/api/solo")
@RequiredArgsConstructor
public class SoloGameController {

    private final SoloGameService soloGameService;

    @Operation(summary = "게임 시작 (보드 발급)",
            description = "서버가 시드 기반 보드를 생성해 반환하고, 검증용 세션을 Redis에 TTL로 보관한다.")
    @PostMapping("/games")
    public ResponseEntity<CustomResponse<SoloResDTO.Start>> start(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        SoloResDTO.Start result = soloGameService.start(userDetails.getUserId());

        CustomResponse<SoloResDTO.Start> response = CustomResponse.onSuccess(GeneralSuccessCode.CREATED, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "게임 종료 (기록 제출)",
            description = "제출된 moves를 시드로 재구성한 보드에 재적용해 검증 후 기록을 저장한다. "
                    + "SOLO404(세션 없음/만료), SOLO400(검증 실패), SOLO409(중복 제출)")
    @PostMapping("/games/{gameSessionId}/finish")
    public ResponseEntity<CustomResponse<SoloResDTO.Finish>> finish(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String gameSessionId,
            @Valid @RequestBody SoloReqDTO.Finish request
    ) {
        SoloResDTO.Finish result = soloGameService.finish(
                userDetails.getUserId(), gameSessionId, request);

        CustomResponse<SoloResDTO.Finish> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "내 기록 조회 (커서 페이지네이션)",
            description = "cursor 미전달 시 최신 기록부터, 전달 시 해당 id 이전 기록을 size개 반환한다.")
    @GetMapping("/records/me")
    public ResponseEntity<CustomResponse<SoloResDTO.RecordPage>> getMyRecords(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        SoloResDTO.RecordPage result = soloGameService.getMyRecords(userDetails.getUserId(), cursor, size);

        CustomResponse<SoloResDTO.RecordPage> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "내 통계 요약",
            description = "최고 점수 / 총 게임 수 / 평균 점수 / 전체 순위를 반환한다.")
    @GetMapping("/records/me/summary")
    public ResponseEntity<CustomResponse<SoloResDTO.Summary>> getSummary(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        SoloResDTO.Summary result = soloGameService.getSummary(userDetails.getUserId());

        CustomResponse<SoloResDTO.Summary> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
