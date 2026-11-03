package com.fuli.trade.config;

import com.fuli.common.api.config.JwtProperties;
import com.fuli.trade.websocket.AuthHandshakeInterceptor;
import com.fuli.trade.websocket.QuoteWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final QuoteWebSocketHandler quoteWebSocketHandler;
    private final JwtProperties jwtProperties;

    /**
     * 允许建立 WebSocket 连接的前端源（逗号分隔，支持通配符，如 {@code *.fuli.com}）。
     * <p>生产环境必须通过 {@code fuli.websocket.allowed-origin-patterns} 显式配置为具体前端域名，
     * 默认仅放行本机开发地址；禁止使用 {@code *} 全放行，否则存在跨站 WebSocket 劫持风险。
     */
    @Value("${fuli.websocket.allowed-origin-patterns:http://localhost:3000,http://127.0.0.1:3000}")
    private String allowedOriginPatterns;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(quoteWebSocketHandler, "/ws/quotes")
                .addInterceptors(new AuthHandshakeInterceptor(jwtProperties))
                .setAllowedOriginPatterns(allowedOriginPatterns.split(","));
    }
}
