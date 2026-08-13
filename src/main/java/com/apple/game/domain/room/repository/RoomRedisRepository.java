package com.apple.game.domain.room.repository;

import com.apple.game.domain.room.entity.RoomStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class RoomRedisRepository {

    private static final Duration ROOM_TTL = Duration.ofHours(6); // 방을 만든 후 6시간 동안 아무 일이 없으면 자동 소멸

    private final StringRedisTemplate redisTemplate;

    public static String roomKey(String code) { return "room:" + code; }
    public static String scoresKey(String code) { return "room:" + code + ":scores"; }
    public static String lockKey(String code) { return "room:" + code + ":lock"; }

    // 방 생성 -> HSETNX(putIfAbsent)로 hostId 필드를 먼저 선점.
    // 같은 코드가 이미 존재하면 false -> 서비스에서 새 코드로 재시도
    public boolean createIfAbsent(String code, Long hostId) {
        String key = roomKey(code);
        Boolean claimed = redisTemplate.opsForHash().putIfAbsent(key, "hostId", String.valueOf(hostId));

        if (!Boolean.TRUE.equals(claimed)) return false;

        redisTemplate.opsForHash().put(key, "status", RoomStatus.WAITING.name());
        redisTemplate.opsForHash().put(key, "round", "0");
        redisTemplate.expire(key, ROOM_TTL);

        return true;
    }

    // HGETALL room:{code} -> 방이 없으면 빈 Map
    public Map<Object, Object> findRoom(String code) {
        return redisTemplate.opsForHash().entries(roomKey(code));
    }

    // 1차: 락 없는 guest 입장 -> 검사(check)는 서비스에서 하고 여기선 그냥 쓴다
    // 두 스레드가 동시에 "guestId 없음"을 읽으면 둘 다 여기 도달한다 -> 정원 초과
    public void setGuestUnsafe(String code, Long guestId) {
        String key = roomKey(code);
        redisTemplate.opsForHash().put(key, "guestId", String.valueOf(guestId));
        redisTemplate.opsForHash().put(key, "status", RoomStatus.READY.name());
    }

    // 방 삭제(혼자 있던 방에서 나가는 경우) -> 누적 점수 키도 같이 정리
    public void deleteRoom(String code) {
        redisTemplate.delete(List.of(roomKey(code), scoresKey(code)));
    }

    // 둘 중 하나가 나가고 한명만 남는 경우 -> 남는 사람을 host로 승격, Waiting으로 돌림
    public void resetToWaiting(String code, Long remainingUserId) {
        String key = roomKey(code);
        redisTemplate.opsForHash().delete(key, "guestId");
        redisTemplate.opsForHash().put(key, "hostId", String.valueOf(remainingUserId));
        redisTemplate.opsForHash().put(key, "status", RoomStatus.WAITING.name());
        redisTemplate.opsForHash().put(key, "round", "0");
        redisTemplate.delete(scoresKey(code)); // 누적 점수 초기화
    }
}
