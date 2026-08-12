package com.apple.game.domain.ranking.controller;

import com.apple.game.domain.ranking.dto.res.RankingResDTO;
import com.apple.game.domain.ranking.service.RankingService;
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

@Tag(name = "랭킹", description = "솔로 모드 랭킹 조회 API")
@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;

    @Operation(summary = "솔로 랭킹 조회",
            description = "period=alltime|weekly. 비로그인도 조회 가능하며 myRank는 로그인 시에만 포함된다. "
                    + "source 필드로 응답 출처(db/redis)를 노출한다.")
    @GetMapping("/solo")
    public ResponseEntity<CustomResponse<RankingResDTO.RankingPage>> getSoloRanking(
            @AuthenticationPrincipal CustomUserDetails userDetails, // permitAll 이라 null 가능
            @RequestParam(defaultValue = "alltime") String period,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = (userDetails != null) ? userDetails.getUserId() : null;
        RankingResDTO.RankingPage result = rankingService.getRanking(userId, period, offset, size);

        CustomResponse<RankingResDTO.RankingPage> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);

        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
