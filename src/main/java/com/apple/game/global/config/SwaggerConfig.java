package com.apple.game.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("사과게임 API")
                        .description("1인용 기록 모드, 2인용 대결 모드가 가능한 사과게임")
                        .version("v1"));
    }
}