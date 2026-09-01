package com.apple.game.domain.room.service.clear;

import com.apple.game.domain.room.entity.RoomStatus;
import com.apple.game.domain.room.repository.RoomRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 1차 — 락 없이 Redis 명령을 하나씩 호출하는 "무방비" 구현.
 *
 * 흐름: HGETALL room → HMGET board → (합 검증) → HDEL board → HINCRBY scores
 * 명령 하나하나는 Redis가 원자적으로 처리하지만, 명령 사이에는 다른 요청이 끼어들 수 있다.
 *
 * 버그 시나리오 (두 명이 같은 사과를 동시에 드래그):
 *   A: HMGET → 합 10 ✔
 *   B: HMGET → 합 10 ✔          ← A가 아직 지우기 전이라 B도 통과 (check와 act 사이의 틈)
 *   A: HDEL, HINCRBY(A)
 *   B: HDEL(이미 없는 필드 → 0개 삭제), HINCRBY(B)  ← 사과는 한 번 사라졌는데 점수는 두 번 오른다
 *
 * Step 8의 join 레이스와 같은 check-then-act 문제. AppleClearConcurrencyTest의 NO_LOCK 케이스가 이를 재현한다.
 */
@Component
@RequiredArgsConstructor
public class NoLockClearExecutor implements ClearExecutor {

    private static final int TARGET_SUM = 10;

    private final StringRedisTemplate redisTemplate;

    @Override
    public ClearStrategy strategy() {
        return ClearStrategy.NO_LOCK;
    }

    @Override
    public ClearOutcome tryClear(String roomCode, Long userId, List<String> fields) {
        String uid = String.valueOf(userId);
        String boardKey = RoomRedisRepository.boardKey(roomCode);

        // ① HGETALL room:{code} — 게임 중인지, 방 멤버인지
        Map<Object, Object> room = redisTemplate.opsForHash().entries(RoomRedisRepository.roomKey(roomCode));
        if (!RoomStatus.PLAYING.name().equals(room.get("status"))) {
            return ClearOutcome.of(ClearOutcome.Status.NOT_PLAYING); // 방이 없어도 status가 null → 여기로
        }
        if (!uid.equals(room.get("hostId")) && !uid.equals(room.get("guestId"))) {
            return ClearOutcome.of(ClearOutcome.Status.NOT_MEMBER);
        }

        // ② HMGET room:{code}:board f1 f2 ... — 지워진 칸은 필드가 없어 null로 온다
        List<Object> values = redisTemplate.opsForHash().multiGet(boardKey, new ArrayList<>(fields));

        int sum = 0;
        List<String> present = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            Object v = values.get(i);
            if (v == null) continue;
            sum += Integer.parseInt((String) v);
            present.add(fields.get(i));
        }
        if (sum > TARGET_SUM) return ClearOutcome.of(ClearOutcome.Status.INVALID_SUM);
        if (sum < TARGET_SUM) return ClearOutcome.of(ClearOutcome.Status.ALREADY_TAKEN); // 0 포함 — 남은 게 없다

        // ───── 여기가 틈. ②에서 본 사과가 ③ 전에 상대에게 지워질 수 있다 ─────

        // ③ HDEL room:{code}:board f1 f2 ... — 반환값(실제 삭제 수)을 확인하지 않는 것이 이 구현의 핵심 결함
        redisTemplate.opsForHash().delete(boardKey, present.toArray());

        // ④ HINCRBY room:{code}:scores {userId} {gained}
        redisTemplate.opsForHash().increment(RoomRedisRepository.scoresKey(roomCode), uid, present.size());

        return ClearOutcome.success(present);
    }
}
