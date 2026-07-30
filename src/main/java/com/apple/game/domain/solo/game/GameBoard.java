package com.apple.game.domain.solo.game;

/**
 * 서버가 moves를 재적용하며 사용하는 가변 보드.
 * 지워진 칸은 0으로 표시한다 (프론트 board.js와 동일한 규칙).
 */
public class GameBoard {

    private final int[][] cells;

    public GameBoard(int[][] cells) {
        this.cells = cells;
    }

    public static GameBoard fromSeed(long seed) {
        return new GameBoard(BoardGenerator.generate(seed));
    }

    /**
     * 사각 영역 (r1,c1)~(r2,c2)를 지운다.
     * @return 제거된 사과 개수. 검증 실패 시 -1.
     */
    public int clear(int r1, int c1, int r2, int c2) {
        // 범위 검증
        if (r1 < 0 || c1 < 0 || r2 >= BoardGenerator.ROWS || c2 >= BoardGenerator.COLS || r1 > r2 || c1 > c2) {
            return -1;
        }

        // 영역 내 0이 아닌 칸의 합 계산
        int sum = 0;
        for (int i = r1; i <= r2; i++) {
            for (int j = c1; j <= c2; j++) {
                sum += cells[i][j];
            }
        }

        // 합이 10인지 아닌지 체크
        if (sum != 10) return -1;

        // 합이 10인 경우 해당 칸 0으로 만들고 제거 갯수 반환
        int removed = 0;
        for (int i = r1; i <= r2; i++) {
            for (int j = c1; j <= c2; j++) {
                if (cells[i][j] != 0) {
                    cells[i][j] = 0;
                    removed++;
                }
            }
        }

        return removed;
    }

    public boolean isEmpty() {
        for (int[] row : cells) {
            for (int cell : row) {
                if (cell != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    // board는 clear()를 통해서만 수정이 되어야함. 읽기만 허용하기 위해 필요한 메서드
    public int[][] snapshot() {
        int[][] copy = new int[cells.length][];

        for (int i = 0; i < cells.length; i++) {
            copy[i] = cells[i].clone();
        }

        return copy;
    }
}

