package com.apple.game.domain.match.controller;

import com.apple.game.domain.match.dto.res.MatchResDTO;
import com.apple.game.domain.match.service.MatchQueryService;
import com.apple.game.global.apiPayload.CustomResponse;
import com.apple.game.global.apiPayload.code.GeneralSuccessCode;
import com.apple.game.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "대전 전적", description = "2인 대전 기록 조회 API")
@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchQueryService matchQueryService;

    @Operation(summary = "내 대전 전적 (커서 페이지네이션)",
            description = "요약(총 n전 n승 n패 n무)과 판별 결과 목록. "
                    + "cursor 미전달 시 최신부터, 전달 시 해당 id 이전 기록을 size개 반환한다.")
    @GetMapping("/me")
    public ResponseEntity<CustomResponse<MatchResDTO.MyMatches>> getMyMatches(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
        MatchResDTO.MyMatches result = matchQueryService.getMyMatches(userDetails.getUserId(), cursor, size);

        CustomResponse<MatchResDTO.MyMatches> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
