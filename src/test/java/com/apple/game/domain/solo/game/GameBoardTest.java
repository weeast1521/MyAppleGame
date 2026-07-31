package com.apple.game.domain.solo.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GameBoard 계약:
 *  - clear(r1,c1,r2,c2): 사각 영역의 합이 정확히 10이면 0이 아닌 칸을 0으로 만들고 제거 개수를 반환
 *  - 실패(범위 밖 / 좌표 역전 / 합≠10) 시 -1을 반환하고 보드는 변하지 않는다
 *  - 지워진 칸(0)은 합 계산에서 0으로 취급 → 같은 사과를 두 번 지우는 조작을 걸러낸다
 */
class GameBoardTest {

    /** 전 칸을 fill로 채운 ROWS×COLS 보드 — 원하는 배치를 얹어서 사용 */
    private static int[][] boardFilledWith(int fill) {
        int[][] cells = new int[BoardGenerator.ROWS][BoardGenerator.COLS];
        for (int[] row : cells) {
            java.util.Arrays.fill(row, fill);
        }
        return cells;
    }

    @Nested
    @DisplayName("clear 성공")
    class ClearSuccess {

        @Test
        @DisplayName("영역 합이 10이면 칸을 0으로 만들고 제거 개수를 반환한다")
        void clearsCellsAndReturnsRemovedCount() {
            // 전부 5 → (0,0)~(0,1) 두 칸의 합이 10
            GameBoard board = new GameBoard(boardFilledWith(5));

            int removed = board.clear(0, 0, 0, 1);

            assertThat(removed).isEqualTo(2);
            assertThat(board.snapshot()[0][0]).isZero();
            assertThat(board.snapshot()[0][1]).isZero();
            // 영역 밖은 그대로
            assertThat(board.snapshot()[0][2]).isEqualTo(5);
        }

        @Test
        @DisplayName("한 칸짜리 영역도 값이 10이 될 수 없으므로 여러 칸 조합만 성공한다 — 5+3+2 세 칸")
        void clearsThreeCellCombination() {
            int[][] cells = boardFilledWith(9);
            cells[3][4] = 5;
            cells[3][5] = 3;
            cells[3][6] = 2;
            GameBoard board = new GameBoard(cells);

            int removed = board.clear(3, 4, 3, 6);

            assertThat(removed).isEqualTo(3);
        }

        @Test
        @DisplayName("이미 지워진 칸(0)은 합에서 제외되고 제거 개수에도 세지 않는다")
        void clearedCellsDoNotCountTowardSumOrRemoved() {
            // 전부 5: 먼저 (0,0)~(0,1)을 지우면 그 자리는 0
            GameBoard board = new GameBoard(boardFilledWith(5));
            board.clear(0, 0, 0, 1);

            // (0,0)~(0,3) = 0 + 0 + 5 + 5 = 10 → 성공하되, 실제 제거는 2칸
            int removed = board.clear(0, 0, 0, 3);

            assertThat(removed).isEqualTo(2);
        }

        @Test
        @DisplayName("보드 경계(마지막 행/열)를 포함한 영역도 지울 수 있다")
        void clearsRegionAtBoardEdge() {
            int lastRow = BoardGenerator.ROWS - 1;
            int lastCol = BoardGenerator.COLS - 1;
            int[][] cells = boardFilledWith(9);
            cells[lastRow][lastCol - 1] = 4;
            cells[lastRow][lastCol] = 6;
            GameBoard board = new GameBoard(cells);

            int removed = board.clear(lastRow, lastCol - 1, lastRow, lastCol);

            assertThat(removed).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("clear 실패 — -1을 반환하고 보드는 변하지 않는다")
    class ClearFailure {

        @Test
        @DisplayName("합이 10이 아니면 실패한다")
        void failsWhenSumIsNot10() {
            GameBoard board = new GameBoard(boardFilledWith(5));

            int result = board.clear(0, 0, 0, 2); // 5+5+5 = 15

            assertThat(result).isEqualTo(-1);
            assertThat(board.snapshot()[0][0]).isEqualTo(5);
            assertThat(board.snapshot()[0][1]).isEqualTo(5);
            assertThat(board.snapshot()[0][2]).isEqualTo(5);
        }

        @Test
        @DisplayName("같은 영역을 두 번 지우면 두 번째는 실패한다 — 중복 득점 조작 방지")
        void failsWhenClearingSameRegionTwice() {
            GameBoard board = new GameBoard(boardFilledWith(5));
            board.clear(0, 0, 0, 1);

            int second = board.clear(0, 0, 0, 1); // 합 = 0

            assertThat(second).isEqualTo(-1);
        }

        @Test
        @DisplayName("보드 범위를 벗어난 좌표는 실패한다")
        void failsWhenOutOfRange() {
            GameBoard board = new GameBoard(boardFilledWith(5));

            assertThat(board.clear(-1, 0, 0, 1)).isEqualTo(-1);
            assertThat(board.clear(0, -1, 0, 1)).isEqualTo(-1);
            assertThat(board.clear(0, 0, BoardGenerator.ROWS, 1)).isEqualTo(-1);
            assertThat(board.clear(0, 0, 0, BoardGenerator.COLS)).isEqualTo(-1);
        }

        @Test
        @DisplayName("좌표가 역전된(r1>r2 또는 c1>c2) 영역은 실패한다")
        void failsWhenCoordinatesAreInverted() {
            GameBoard board = new GameBoard(boardFilledWith(5));

            assertThat(board.clear(1, 0, 0, 1)).isEqualTo(-1);
            assertThat(board.clear(0, 1, 0, 0)).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("isEmpty")
    class IsEmpty {

        @Test
        @DisplayName("살아있는 사과가 하나라도 있으면 false다")
        void falseWhenAnyCellRemains() {
            GameBoard board = new GameBoard(boardFilledWith(5));

            assertThat(board.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("모든 칸이 지워지면 true다")
        void trueWhenAllCellsCleared() {
            // 전부 5 → 가로 두 칸씩 지우면 전체를 비울 수 있다 (COLS=17은 홀수이므로 세로 조합 병행)
            GameBoard board = new GameBoard(boardFilledWith(5));
            for (int r = 0; r < BoardGenerator.ROWS; r++) {
                for (int c = 0; c + 1 < BoardGenerator.COLS; c += 2) {
                    board.clear(r, c, r, c + 1);
                }
            }
            // 남은 마지막 열은 세로 두 칸씩 (ROWS=10은 짝수)
            int lastCol = BoardGenerator.COLS - 1;
            for (int r = 0; r + 1 < BoardGenerator.ROWS; r += 2) {
                board.clear(r, lastCol, r + 1, lastCol);
            }

            assertThat(board.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("fromSeed")
    class FromSeed {

        @Test
        @DisplayName("fromSeed는 BoardGenerator.generate와 같은 보드를 만든다 — finish 재구성 경로")
        void fromSeedMatchesGeneratorOutput() {
            long seed = 550_8400L;

            GameBoard board = GameBoard.fromSeed(seed);

            assertThat(board.snapshot()).isDeepEqualTo(BoardGenerator.generate(seed));
        }
    }
}
