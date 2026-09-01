package com.apple.game.domain.room.service.clear;

import com.apple.game.domain.room.repository.RoomRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 3차 — Lua 스크립트로 [검증 + HDEL + HINCRBY]를 한 번의 왕복으로 원자 실행하는 구현. ← 최종 채택
 *
 * Redis는 명령을 단일 스레드로 처리하고, EVAL로 넘긴 스크립트는 통째로 하나의 명령이다.
 * 따라서 스크립트가 도는 동안 다른 클라이언트의 명령은 끼어들 수 없다 — check와 act 사이의 틈이 아예 없다.
 * 락과 비교하면: 락은 "틈을 잠근다", Lua는 "틈을 없앤다". 왕복 1번, 대기 없음, 데드락 없음.
 *
 * Step 8(JOIN_SCRIPT)·Step 9(READY_SCRIPT)와 같은 접근이며, 이번엔 반환값이 여러 개(지운 필드 목록)라
 * 문자열 대신 Lua 테이블(배열)로 돌려준다. 첫 원소가 상태, 나머지가 지운 "r:c".
 *
 * 주의: 스크립트 안에서는 오래 걸리는 일을 하면 안 된다 — 도는 동안 Redis 전체가 멈춘다.
 * 여기서는 최대 170칸 HMGET + HDEL 정도라 마이크로초 단위.
 */
@Component
@RequiredArgsConstructor
public class LuaClearExecutor implements ClearExecutor {

    // KEYS[1] = room:{code}          (Hash: status, hostId, guestId)
    // KEYS[2] = room:{code}:board    (Hash: "r:c" -> 숫자, 지워진 칸은 필드 없음)
    // KEYS[3] = room:{code}:scores   (Hash: userId -> 이번 판 점수)
    // ARGV[1] = userId, ARGV[2..n] = 선택 영역의 "r:c" 필드들
    //
    // Redis Lua 규칙: HMGET에서 없는 필드는 nil이 아니라 false로 온다 → `if v then`으로 거른다.
    private static final DefaultRedisScript<List> CLEAR_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') ~= 'PLAYING' then
                return {'NOT_PLAYING'}
            end
            local hostId = redis.call('HGET', KEYS[1], 'hostId')
            local guestId = redis.call('HGET', KEYS[1], 'guestId')
            if ARGV[1] ~= hostId and ARGV[1] ~= guestId then
                return {'NOT_MEMBER'}
            end

            local values = redis.call('HMGET', KEYS[2], unpack(ARGV, 2, #ARGV))
            local sum = 0
            local present = {}
            for i = 2, #ARGV do
                local v = values[i - 1]
                if v then
                    sum = sum + tonumber(v)
                    present[#present + 1] = ARGV[i]
                end
            end
            if sum > 10 then
                return {'INVALID_SUM'}
            end
            if sum < 10 then
                return {'ALREADY_TAKEN'}
            end

            redis.call('HDEL', KEYS[2], unpack(present))
            redis.call('HINCRBY', KEYS[3], ARGV[1], #present)

            local result = {'SUCCESS'}
            for _, f in ipairs(present) do
                result[#result + 1] = f
            end
            return result
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public ClearStrategy strategy() {
        return ClearStrategy.LUA;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ClearOutcome tryClear(String roomCode, Long userId, List<String> fields) {
        List<String> args = new ArrayList<>(fields.size() + 1);
        args.add(String.valueOf(userId));
        args.addAll(fields);

        List<Object> result = redisTemplate.execute(
                CLEAR_SCRIPT,
                List.of(RoomRedisRepository.roomKey(roomCode),
                        RoomRedisRepository.boardKey(roomCode),
                        RoomRedisRepository.scoresKey(roomCode)),
                args.toArray());

        String status = (String) result.get(0);
        if (!"SUCCESS".equals(status)) {
            return ClearOutcome.of(ClearOutcome.Status.valueOf(status));
        }
        List<String> cleared = result.subList(1, result.size()).stream().map(String::valueOf).toList();
        return ClearOutcome.success(cleared);
    }
}
