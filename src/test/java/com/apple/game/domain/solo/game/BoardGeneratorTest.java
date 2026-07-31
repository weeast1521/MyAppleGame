package com.apple.game.domain.solo.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BoardGenerator 계약:
 *  - generate(seed)는 ROWS(10) × COLS(17)의 int[][]를 반환한다
 *  - 모든 셀 값은 1~9
 *  - 같은 시드 → 항상 같은 보드 (finish 재검증의 전제)
 *  - 매 호출마다 새 배열을 반환한다 (재적용 중 보드를 수정해도 서로 영향 없음)
 */
class BoardGeneratorTest {

    @Test
    @DisplayName("보드는 10행 17열이다")
    void boardHas10RowsAnd17Cols() {
        int[][] board = BoardGenerator.generate(1L);

        assertThat(board).hasNumberOfRows(BoardGenerator.ROWS);

        for (int[] row : board) {
            assertThat(row).hasSize(BoardGenerator.COLS);
        }
    }

    @Test
    @DisplayName("모든 셀 값은 1 이상 9 이하다")
    void allCellsAreBetween1And9() {
        int[][] board = BoardGenerator.generate(1L);

        for (int[] row : board) {
            for (int value : row) {
                assertThat(value).isBetween(1, 9);
            }
        }
    }

    @Test
    @DisplayName("같은 시드로 생성하면 항상 같은 보드가 나온다 — 재검증의 전제")
    void sameSeedProducesSameBoard() {
        int[][] first = BoardGenerator.generate(550_8400L);
        int[][] second = BoardGenerator.generate(550_8400L);

        assertThat(first).isDeepEqualTo(second);
    }

    @Test
    @DisplayName("다른 시드로 생성하면 다른 보드가 나온다")
    void differentSeedsProduceDifferentBoards() {
        int[][] first = BoardGenerator.generate(1L);
        int[][] second = BoardGenerator.generate(2L);

        // 170칸이 전부 우연히 일치할 확률은 사실상 0 — 시드가 반영되지 않는 구현을 잡아낸다
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("반환된 보드를 수정해도 다음 generate 결과에 영향이 없다")
    void returnedBoardIsIndependentCopy() {
        int[][] first = BoardGenerator.generate(1L);
        first[0][0] = 0; // finish 재검증 중 보드를 수정하는 상황

        int[][] second = BoardGenerator.generate(1L);

        assertThat(second[0][0]).isBetween(1, 9);
    }
}
