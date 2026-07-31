package com.apple.game.domain.solo.game;

import java.util.Random;

public class BoardGenerator {

    // 17 * 10
    public static final int COLS = 17;
    public static final int ROWS = 10;

    private BoardGenerator(){

    }

    /**
     * 같은 seed면 항상 같은 보드를 만든다.
     * java.util.Random은 알고리즘이 스펙에 고정되어 있어 JVM/OS가 달라도 결과가 같다.
     * → finish에서 보드를 DB에 저장하지 않고 seed만으로 재구성할 수 있는 근거.
     * 현재 = 일단은 단순 랜덤 생성, 이후 합을 10의 배수, 합이 10이 되는 숫자들의 숫자 묶음으로 보드 채우기로 보완해 나갈 예정
     */
    public static int[][] generate(long seed) {
        Random random = new Random(seed);

        int[][] board = new int[ROWS][COLS];

        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < COLS; j++) {
                board[i][j] = random.nextInt(9) + 1;
            }
        }

        return board;
    }
}
