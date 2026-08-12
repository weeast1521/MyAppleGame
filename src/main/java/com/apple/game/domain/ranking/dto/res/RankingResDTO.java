package com.apple.game.domain.ranking.dto.res;

import java.util.List;

public class RankingResDTO {

    public record RankingPage(
            String source, // db|redis
            List<RankingItem> rankings,
            MyRank myRank // null 허용 -> 비로그인 상태인 경우
    ){
    }

    public record RankingItem(int rank, String nickname, int score) {}

    public record MyRank(int rank, int score) {}
}
