package com.apple.game.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 판 종료 타이머용 스케줄러.
 * 주의: 인메모리다 — 앱이 재시작(재배포·blue-green 전환)되면 등록된 타이머는 사라진다.
 * 그 판은 PLAYING 상태로 남는데, 진행 중이던 WebSocket도 함께 끊기므로 실질 피해는
 * "그 판이 전적에 안 남는 것"뿐이고 다음 ready부터 정상 진행된다(수용 — Step 12에서 재검토).
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler gameTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2); // 동시 진행 판 수가 적다 — 타이머는 판당 1개, 실행은 수 ms
        scheduler.setThreadNamePrefix("game-end-");
        return scheduler;
    }
}
