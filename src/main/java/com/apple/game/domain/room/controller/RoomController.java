package com.apple.game.domain.room.controller;

import com.apple.game.domain.room.dto.res.RoomResDTO;
import com.apple.game.domain.room.service.RoomService;
import com.apple.game.global.apiPayload.CustomResponse;
import com.apple.game.global.apiPayload.code.GeneralSuccessCode;
import com.apple.game.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Room", description = "2인 대전 방 API")
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @Operation(summary = "방 생성", description = "짧은 방 코드를 발급하고 Redis Hash에 방 상태를 저장한다.")
    @PostMapping
    public ResponseEntity<CustomResponse<RoomResDTO.Create>> create(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        RoomResDTO.Create result = roomService.create(userDetails.getUserId());
        CustomResponse<RoomResDTO.Create> response = CustomResponse.onSuccess(GeneralSuccessCode.CREATED, result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "방 입장",
            description = "정원(2명) 검사 후 입장. ROOM404(없는 방), ROOM409(정원 초과), ROOM409_1(진행 중)")
    @PostMapping("/{roomCode}/join")
    public ResponseEntity<CustomResponse<RoomResDTO.Join>> join(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String roomCode
    ) {
        RoomResDTO.Join result = roomService.join(userDetails.getUserId(), roomCode.toUpperCase());
        CustomResponse<RoomResDTO.Join> response = CustomResponse.onSuccess(GeneralSuccessCode.OK, result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    @Operation(summary = "방 나가기", description = "방 정리 + 누적 점수 키 삭제. PLAYER_LEFT 브로드캐스트는 Step 9에서 추가.")
    @DeleteMapping("/{roomCode}/leave")
    public ResponseEntity<CustomResponse<Void>> leave(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable String roomCode
    ) {
        roomService.leave(userDetails.getUserId(), roomCode.toUpperCase());
        CustomResponse<Void> response = CustomResponse.onSuccess(GeneralSuccessCode.OK);
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}
