package com.apple.game.domain.room;

import com.apple.game.domain.room.repository.RoomRedisRepository;
import com.apple.game.domain.room.service.RoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로덕션 버그 재현: 게스트가 나갔다가 다시 들어오면 게임이 시작되지 않던 문제.
 * leave가 ready SET을 통째로 지우면서 남은 호스트의 ready까지 사라졌고,
 * 호스트 프론트는 ready를 연결 시 한 번만 보내므로 재-ready 트리거가 없었다.
 * READY_SCRIPT(Lua)는 유저 ID 문자열만 보므로 DB 유저 없이 Redis만으로 방을 차린다.
 */
@SpringBootTest
@ActiveProfiles("local")
class RoomRejoinAfterLeaveTest {

    private static final Long HOST_ID = 920_001L;
    private static final Long GUEST_ID = 920_002L;

    @Autowired RoomRedisRepository roomRedisRepository;
    @Autowired RoomService roomService;

    private String roomCode;

    @BeforeEach
    void setUp() {
        roomCode = "RJN" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        assertThat(roomRedisRepository.createIfAbsent(roomCode, HOST_ID)).isTrue();
        assertThat(roomRedisRepository.readyAtomic(roomCode, HOST_ID)).isEqualTo("WAIT"); // 호스트 연결 = ready
    }

    @AfterEach
    void tearDown() {
        roomRedisRepository.deleteRoom(roomCode);
    }

    @Test
    @DisplayName("게스트가 나갔다가 다시 들어와 ready하면 게임이 시작된다 — 남은 호스트의 ready가 보존된다")
    void rejoinAfterLeaveStartsGame() {
        // 1판 정상 시작
        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("OK");
        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("START:1");

        // 판 종료 후 대기 상태(READY)에서 게스트가 나감
        roomRedisRepository.finishRound(roomCode);
        roomService.leave(GUEST_ID, roomCode);
        assertThat(roomRedisRepository.findRoom(roomCode)).containsEntry("status", "WAITING").doesNotContainKey("guestId");

        // 같은 게스트가 재입장 → 연결 시 ready 1회 (프론트 동작 그대로)
        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("OK");
        String result = roomRedisRepository.readyAtomic(roomCode, GUEST_ID);

        assertThat(result).as("호스트 ready가 복원돼 있어야 두 번째 ready에서 START").isEqualTo("START:1");
    }

    @Test
    @DisplayName("멤버는 게임 중(PLAYING)에도 join하면 REJOIN — 상태는 바뀌지 않고, 비멤버는 여전히 PLAYING으로 거절")
    void memberCanRejoinWhilePlaying() {
        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("OK");
        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("START:1");

        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("REJOIN"); // 새로고침한 게스트
        assertThat(roomRedisRepository.joinAtomic(roomCode, HOST_ID)).isEqualTo("REJOIN");  // 새로고침한 호스트
        assertThat(roomRedisRepository.findRoom(roomCode)).containsEntry("status", "PLAYING").containsEntry("guestId", String.valueOf(GUEST_ID));
        assertThat(roomRedisRepository.joinAtomic(roomCode, 920_009L)).isEqualTo("PLAYING"); // 제3자
    }

    @Test
    @DisplayName("호스트 혼자 대기 중 탭을 닫고 돌아오면 REJOIN — 자기 방을 되찾는다")
    void hostCanRejoinOwnWaitingRoom() {
        assertThat(roomRedisRepository.joinAtomic(roomCode, HOST_ID)).isEqualTo("REJOIN");
        assertThat(roomRedisRepository.findRoom(roomCode)).containsEntry("status", "WAITING");
    }

    @Test
    @DisplayName("호스트가 나가면 남은 게스트가 호스트로 승격되고, 그의 ready도 보존된다")
    void hostLeavesGuestPromotedWithReady() {
        assertThat(roomRedisRepository.joinAtomic(roomCode, GUEST_ID)).isEqualTo("OK");
        assertThat(roomRedisRepository.readyAtomic(roomCode, GUEST_ID)).isEqualTo("START:1");
        roomRedisRepository.finishRound(roomCode);

        roomService.leave(HOST_ID, roomCode);
        assertThat(roomRedisRepository.findRoom(roomCode)).containsEntry("hostId", String.valueOf(GUEST_ID));

        Long newcomer = 920_003L;
        assertThat(roomRedisRepository.joinAtomic(roomCode, newcomer)).isEqualTo("OK");
        assertThat(roomRedisRepository.readyAtomic(roomCode, newcomer)).isEqualTo("START:1");
    }
}
