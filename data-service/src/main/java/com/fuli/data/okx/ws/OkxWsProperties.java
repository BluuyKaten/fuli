package com.fuli.data.okx.ws;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OKX WebSocket 配置（实施计划 P3）。
 *
 * <p><b>实盘域是硬禁止的</b>：URL 必须是模拟盘 {@code wss://wspap.okx.com/...}。
 * REST 侧靠恒定的 {@code x-simulated-trading: 1} 头隔离，WS 侧则靠<b>不同域名</b>隔离
 * （okx3.txt「模拟盘交易」章节）。配成 {@code ws.okx.com} 会在启动时直接失败，
 * 这是 ADR-0009 决策 4 在 WS 链路上的延伸，不能只靠「别填错」的约定。
 */
@Data
@Component
@ConfigurationProperties(prefix = "okx.ws")
public class OkxWsProperties {

    /** 默认关闭：起服务不主动连交易所，避免本地开发踩限频。 */
    private boolean enabled = false;

    /** 私有频道（orders / account / positions 需要登录）。公共频道另起连接时改这里。 */
    private String url = "wss://wspap.okx.com/ws/v5/private";

    private long reconnectDelayMs = 2000L;

    private long maxReconnectDelayMs = 60000L;

    /** 应用层心跳间隔；OKX 建议 25s 内发一次 ping，这里留余量。 */
    private int heartbeatSeconds = 20;

    /** 有界环形缓冲容量，满了丢最旧帧（背压），绝不无限堆积。 */
    private int bufferCapacity = 10000;

    /**
     * 订阅项，重连后自动重放。支持两种写法：
     * <ul>
     *   <li>{@code account} / {@code orders:ANY} / {@code positions:SWAP}（简写）</li>
     *   <li>{@code {"channel":"orders","instType":"ANY"}}（原样 JSON）</li>
     * </ul>
     */
    private List<String> subscriptions = List.of("account", "positions:SWAP", "orders:ANY");

    private static final String DEMO_WS_PREFIX = "wss://wspap.okx.com/";

    @PostConstruct
    public void validate() {
        if (url == null || !url.startsWith(DEMO_WS_PREFIX)) {
            throw new IllegalStateException(
                    "OKX WebSocket 只允许模拟盘域 " + DEMO_WS_PREFIX + "（ADR-0009 决策 4），当前：" + url
                            + "。实盘凭证不进 Java 服务，WS 侧也不允许连实盘域。");
        }
        if (bufferCapacity <= 0) {
            throw new IllegalStateException("okx.ws.buffer-capacity 必须为正数");
        }
        if (heartbeatSeconds <= 0 || reconnectDelayMs <= 0 || maxReconnectDelayMs < reconnectDelayMs) {
            throw new IllegalStateException("okx.ws 心跳/重连参数不合法");
        }
        // 公共端点订阅私有频道会被交易所拒订，报错信息很含糊（"channel doesn't exist"），
        // 这里提前拦住，直接说清「你的 URL 是 public 但订阅里有 account」。
        if (url.contains("/public")) {
            for (String spec : subscriptions) {
                String channel = (spec == null ? "" : spec.trim()).split(":", 2)[0];
                if ("account".equals(channel) || "orders".equals(channel) || "positions".equals(channel)) {
                    throw new IllegalStateException("okx.ws.url 是公共端点（/public），不能订阅私有频道 "
                            + channel + "；私有频道请使用 /private 端点并配置 okx.ws-owner-user-id");
                }
            }
        }
    }
}
