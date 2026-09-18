package com.apple.game.domain.solo;

import com.apple.game.domain.solo.dto.req.SoloReqDTO;
import com.apple.game.domain.solo.dto.res.SoloResDTO;
import com.apple.game.domain.solo.entity.SoloRecord;
import com.apple.game.domain.solo.exception.SoloErrorCode;
import com.apple.game.domain.solo.repository.SoloRecordRepository;
import com.apple.game.domain.solo.service.SoloGameService;
import com.apple.game.domain.solo.session.SoloGameRepository;
import com.apple.game.domain.solo.session.SoloGameSession;
import com.apple.game.domain.user.entity.User;
import com.apple.game.domain.user.repository.UserRepository;
import com.apple.game.global.exception.CustomException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #49 — 제한시간 준수 검증을 세션 TTL에서 분리한 결과를 확인한다.
 *
 * 수정 전: TTL 150초가 '제출 창'과 '제한시간 준수' 두 역할을 겸해서, 브라우저 타이머가
 *          멈춘 만큼 제출이 밀리면 정상 플레이도 SOLO404로 거부됐다.
 * 수정 후: moves의 elapsedMs가 제한시간을 검증하고(SOLO400_1), TTL은 제출 창(600초)만 맡는다.
 *          따라서 오래 지연된 제출도 세션이 살아 있으면 저장되고, 제한시간을 벗어난
 *          기록은 지연과 무관하게 거부된다.
 */
@SpringBootTest
@ActiveProfiles("local")
class SoloFinishTimeTest {

    private static final int TIME_LIMIT_SECONDS = 120;

    @Autowired SoloGameService soloGameService;
    @Autowired SoloGameRepository soloGameRepository;
    @Autowired SoloRecordRepository soloRecordRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedisTemplate<String, Object> redisTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.createLocalUser(
                "solo-time-" + UUID.randomUUID() + "@test.com", "pw", "시간검증"));
    }

    @AfterEach
    void tearDown() {
        soloRecordRepository.deleteAll(soloRecordRepository.findAll().stream()
                .filter(r -> r.getUser().getId().equals(user.getId()))
                .toList());
        userRepository.delete(user);
    }

    @Test
    @DisplayName("세션 TTL은 제출 창 600초 — 제한시간(120초)에서 파생되지 않는다")
    void sessionTtlIsSubmitWindow() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        Long ttl = redisTemplate.getExpire("solo:session:" + start.gameSessionId());

        // 저장 직후 조회라 600초에 근접한다. 150초(옛 값)보다 확실히 크다는 것이 요점이다.
        assertThat(ttl).isGreaterThan(TIME_LIMIT_SECONDS + 30L);
        assertThat(ttl).isBetween(590L, 600L);
    }

    @Test
    @DisplayName("제한시간이 한참 지나 도착한 제출도 저장된다 — TTL이 살아 있으면 지연은 거부 사유가 아니다")
    void lateSubmitIsAccepted() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        // 브라우저가 5분간 얼어 있었던 상황: 세션 시작 시각만 과거로 되돌려 같은 조건을 만든다
        SoloGameSession frozen = SoloGameSession.create(
                start.gameSessionId(), user.getId(), Long.parseLong(start.boardSeed()),
                System.currentTimeMillis() - Duration.ofMinutes(5).toMillis());
        soloGameRepository.save(frozen, Duration.ofSeconds(600));

        // moves가 비어 있어도 '한 판을 마쳤다'는 사실은 기록된다 (점수 0)
        SoloResDTO.Finish finish = soloGameService.finish(
                user.getId(), start.gameSessionId(), new SoloReqDTO.Finish(List.of()));

        assertThat(finish.score()).isZero();

        SoloRecord saved = soloRecordRepository.findById(finish.recordId()).orElseThrow();
        // 플레이 시간은 제한시간을 상한으로 — 얼어 있던 5분이 플레이 시간으로 새지 않는다
        assertThat(saved.getPlayTimeSeconds()).isEqualTo(TIME_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("제한시간을 넘긴 move가 있으면 SOLO400_1 — 좌표를 재생하기 전에 거부한다")
    void movesAfterTimeLimitRejected() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        // 좌표는 범위를 벗어난 값 — 시각 검증이 먼저 걸리므로 보드 재생까지 가지 않는다
        SoloReqDTO.Finish request = new SoloReqDTO.Finish(List.of(
                new SoloReqDTO.Move(-1, -1, 999, 999, 1_000L),
                new SoloReqDTO.Move(-1, -1, 999, 999, TIME_LIMIT_SECONDS * 1000L + 5_000L)));

        assertThatThrownBy(() -> soloGameService.finish(user.getId(), start.gameSessionId(), request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", SoloErrorCode.MOVES_OUT_OF_TIME);
    }

    @Test
    @DisplayName("move 시각이 단조 증가하지 않으면 SOLO400_1 — 클라이언트가 기록을 조립한 것")
    void nonMonotonicMoveTimesRejected() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        SoloReqDTO.Finish request = new SoloReqDTO.Finish(List.of(
                new SoloReqDTO.Move(-1, -1, 999, 999, 10_000L),
                new SoloReqDTO.Move(-1, -1, 999, 999, 5_000L)));

        assertThatThrownBy(() -> soloGameService.finish(user.getId(), start.gameSessionId(), request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", SoloErrorCode.MOVES_OUT_OF_TIME);
    }

    @Test
    @DisplayName("제한시간 직후 1초 오차는 허용 — 250ms 타이머가 마지막 드래그를 늦게 찍을 수 있다")
    void graceWithinOneSecondAccepted() {
        SoloResDTO.Start start = soloGameService.start(user.getId());

        // 시각은 통과하고 좌표 검증으로 넘어가는지만 본다 (범위를 벗어난 좌표라 INVALID_MOVES가 되어야 한다)
        SoloReqDTO.Finish request = new SoloReqDTO.Finish(List.of(
                new SoloReqDTO.Move(-1, -1, 999, 999, TIME_LIMIT_SECONDS * 1000L + 500L)));

        assertThatThrownBy(() -> soloGameService.finish(user.getId(), start.gameSessionId(), request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("code", SoloErrorCode.INVALID_MOVES);
    }
}
