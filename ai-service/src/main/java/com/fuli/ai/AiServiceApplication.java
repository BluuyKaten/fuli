package com.fuli.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agent 编排层（实施计划 P9 / ADR-0010 决策 4）。
 *
 * <p>经网关 /api/ai/** 暴露；服务内自己校验用户令牌（不信任 X-User-Id）。
 * 调用 data-service 时一律用**自己的服务令牌**（scope=orders:read），
 * 从不转发用户令牌——禁止 token passthrough。
 *
 * <p>{@code @EnableScheduling} 供 {@code ServiceTokenProvider} 定时刷新服务令牌。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
