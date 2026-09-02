package com.apple.game.domain.match.entity;

public enum MatchResult {
    WIN,
    LOSE,
    DRAW,
    FORFEIT_WIN, // 상대 이탈로 인한 몰수승 (Step 12에서 사용)
    FORFEIT_LOSE
}
