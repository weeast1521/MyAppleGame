package com.apple.game.domain.room.dto.ws;

import com.apple.game.domain.solo.game.BoardGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 프론트가 /app/room/{code}/clear 로 발행하는 사과 제거 요청.
 * (r1,c1)~(r2,c2)는 드래그한 사각 영역 — 프론트(board.js)가 min/max로 정규화해서 보내지만 서버는 그걸 믿지 않는다.
 * requestId는 프론트가 요청마다 새로 만드는 UUID — 재전송 중복 처리를 막는 멱등 키.
 */
public record ClearRequest(String requestId, int r1, int c1, int r2, int c2) {

    // 범위 검증 — Redis에 가기 전에 끝낼 수 있는 순수 검증 (실패 시 CLEAR_REJECTED: INVALID_RANGE)
    public boolean isValidRange() {
        return r1 >= 0 && c1 >= 0
                && r2 < BoardGenerator.ROWS && c2 < BoardGenerator.COLS
                && r1 <= r2 && c1 <= c2;
    }

    // 사각 영역 → 보드 Hash의 필드 목록("r:c"). Step 9에서 보드를 셀 단위 Hash로 저장한 이유가 이 변환이다.
    // 이미 지워진 칸도 포함해서 넘긴다 — 지워진 칸은 Hash에 필드가 없으므로 HMGET이 nil을 돌려주고 합산에서 빠진다.
    public List<String> fields() {
        List<String> fields = new ArrayList<>();
        for (int r = r1; r <= r2; r++) {
            for (int c = c1; c <= c2; c++) {
                fields.add(r + ":" + c);
            }
        }
        return fields;
    }
}
