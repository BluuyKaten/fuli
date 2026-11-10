package com.fuli.data.okx.ws;

import com.fuli.data.config.OkxConfig;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxCredentialService;
import com.fuli.data.okx.OkxCredentials;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * OKX WebSocket 客户端（实施计划 P3），基于 JDK 17 内置 {@link HttpClient#newWebSocketBuilder()}，
 * 不引入第三方 WS 库，与现有 REST 侧 {@code OkxClient} 的技术栈保持一致。
 *
 * <p>职责边界：<b>只管连接、登录、订阅重放、重连、心跳与背压入缓冲</b>，
 * 不做业务解码、不写 MySQL。频道数据的解码与订单状态机留给 P4，
 * 通过 {@link #addHandler} 注册消费方。
 *
 * <p>默认关闭（{@code okx.ws.enabled=false}），避免本地起服务就连交易所。
 * URL 被 {@link OkxWsProperties#validate()} 强制限制在模拟盘域 wspap.okx.com。
 */
@Slf4j
@Component
public class OkxWsClient {

    public enum Status { NEW, CONNECTING, LOGGED_IN, RECONNECTING, CLOSED }

    private final OkxWsProperties properties;

    private final OkxConfig okxConfig;

    private final OkxCredentialService credentialService;

    private final OkxWsFrameBuffer<String> buffer;

    private final List<Consumer<String>> handlers = new CopyOnWriteArrayList<>();

    private final AtomicReference<Status> status = new AtomicReference<>(Status.NEW);

    /** WS 与 REST 共用同一个出网代理：wspap 域名在国内容易被解析到黑洞地址。 */
    private final HttpClient http;

    /** 最近一次收到非 pong 帧的时间（毫秒），供 /ws/status 判断「连着但没数据」。 */
    private volatile long lastFrameAt = 0L;

    /** 最近一帧的预览（截断），真机联调时用来确认订阅 ACK / 错误码。 */
    private volatile String lastFramePreview = null;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "okx-ws");
        t.setDaemon(true);
        return t;
    });

    private volatile WebSocket socket;

    private volatile boolean running = false;

    private volatile int reconnectAttempt = 0;

    private volatile boolean subscribed = false;

    public OkxWsClient(OkxWsProperties properties, OkxConfig okxConfig,
                       OkxCredentialService credentialService) {
        this.properties = properties;
        this.okxConfig = okxConfig;
        this.credentialService = credentialService;
        this.buffer = new OkxWsFrameBuffer<>(properties.getBufferCapacity());
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        InetSocketAddress proxy = OkxClient.proxyAddress(okxConfig.getProxy());
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
            log.info("[OKX-WS] 走代理 {}:{}", proxy.getHostString(), proxy.getPort());
        }
        this.http = builder.build();
    }

    @PostConstruct
    public void autoStart() {
        if (properties.isEnabled()) {
            start();
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        subscribed = false;
        connect();
        scheduler.scheduleAtFixedRate(this::heartbeat,
                properties.getHeartbeatSeconds(), properties.getHeartbeatSeconds(), TimeUnit.SECONDS);
    }

    /** kill switch：立即断连、停止心跳与重连，且不改任何配置。 */
    @PreDestroy
    public void stop() {
        running = false;
        status.set(Status.CLOSED);
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "stop").exceptionally(e -> null);
        }
        scheduler.shutdownNow();
        log.info("[OKX-WS] 已停止，丢弃帧累计 {}", buffer.droppedCount());
    }

    /** 注册消息消费方（P4 的频道解码器）。回调里抛异常不会影响其它消费方或连接。 */
    public void addHandler(Consumer<String> handler) {
        handlers.add(handler);
    }

    public Status getStatus() {
        return status.get();
    }

    public OkxWsFrameBuffer<String> getBuffer() {
        return buffer;
    }

    public long droppedFrames() {
        return buffer.droppedCount();
    }

    public long getLastFrameAt() {
        return lastFrameAt;
    }

    public String getLastFramePreview() {
        return lastFramePreview;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String getUrl() {
        return properties.getUrl();
    }

    public java.util.List<String> getSubscriptions() {
        return properties.getSubscriptions();
    }

    private void connect() {
        if (!running) {
            return;
        }
        status.set(Status.CONNECTING);
        try {
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(properties.getUrl()), new Listener())
                    .whenComplete((ws, error) -> {
                        if (error != null) {
                            log.warn("[OKX-WS] 连接失败：{}", error.getMessage());
                            scheduleReconnect();
                        } else {
                            onConnected(ws);
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("[OKX-WS] 建连异常：{}", e.getMessage());
            scheduleReconnect();
        }
    }

    private void onConnected(WebSocket ws) {
        if (!running) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "closed").exceptionally(e -> null);
            return;
        }
        socket = ws;
        reconnectAttempt = 0;
        subscribed = false;
        OkxCredentials credentials = resolveCredentials();
        if (credentials != null) {
            send(OkxWsLogin.message(credentials.apiKey(), credentials.passphrase(),
                    credentials.secretKey()));
            // 真正订阅要等 event=login 且 code=0，见 onText
        } else {
            log.warn("[OKX-WS] 没有可用的账户凭证（okx.ws-owner-user-id），私有频道无法登录；仅公共频道可用");
            subscribe();
        }
    }

    /**
     * WS 是后台长连接，没有「当前请求的 JWT」，所以必须显式指定账户身份再取凭证。
     *
     * <p>这是**身份选择**而非凭证回退：凭证仍然只来自 {@code okx_account} 的密文。
     * 未配置身份或该用户没录 Key 时返回 null，连接继续跑公共频道，不静默换用别人的账户。
     */
    private OkxCredentials resolveCredentials() {
        long owner = okxConfig.getWsOwnerUserId();
        if (owner <= 0) {
            return null;
        }
        try {
            return credentialService.findEnabled(owner).orElse(null);
        } catch (RuntimeException e) {
            // 解密失败等异常：只报警，不让 WS 线程崩掉；公共频道仍应可用
            log.error("[OKX-WS] 解析账户凭证失败 ownerUserId={}：{}", owner, e.getMessage());
            return null;
        }
    }

    /** 收到一帧文本：先入有界缓冲（背压），再分发；登录 ACK 在这里触发订阅重放。 */
    public void onText(String text) {
        lastFrameAt = System.currentTimeMillis();
        lastFramePreview = text != null && text.length() > 240 ? text.substring(0, 240) + "..." : text;
        buffer.offer(text);
        if (isLoginAck(text)) {
            subscribe();
        }
        for (Consumer<String> handler : handlers) {
            try {
                handler.accept(text);
            } catch (RuntimeException e) {
                // 用 toString 而不是 getMessage：NPE 之类常常 message 为 null，
                // 之前只打 message 会得到一句空白的「消息处理器异常：」，等于没打
                log.warn("[OKX-WS] 消息处理器异常：{}", e.toString(), e);
            }
        }
    }

    private static boolean isLoginAck(String text) {
        return text != null && text.contains("\"event\":\"login\"") && text.contains("\"code\":\"0\"");
    }

    private void subscribe() {
        if (subscribed || !running) {
            return;
        }
        subscribed = true;
        status.set(Status.LOGGED_IN);
        List<String> specs = properties.getSubscriptions();
        if (specs == null || specs.isEmpty()) {
            return;
        }
        send(OkxWsSubscription.subscribeMessage(specs));
        log.info("[OKX-WS] 已重放 {} 条订阅", specs.size());
    }

    private void heartbeat() {
        if (running && status.get() == Status.LOGGED_IN) {
            send("ping");
        }
    }

    private void send(String message) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        ws.sendText(message, true).exceptionally(e -> {
            log.warn("[OKX-WS] 发送失败：{}", e.getMessage());
            return null;
        });
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        status.set(Status.RECONNECTING);
        long delay = reconnectDelay(reconnectAttempt++, properties.getReconnectDelayMs(),
                properties.getMaxReconnectDelayMs());
        log.info("[OKX-WS] {}ms 后重连（第 {} 次）", delay, reconnectAttempt);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /** 指数退避，封顶 max；用循环而不是位移，避免 attempt 大时溢出成负数。 */
    static long reconnectDelay(int attempt, long base, long max) {
        long value = base;
        for (int i = 0; i < Math.min(Math.max(attempt, 0), 20); i++) {
            if (value > max / 2) {
                return max;
            }
            value *= 2;
        }
        return Math.min(value, max);
    }

    private class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                if (!"pong".equals(text)) {
                    OkxWsClient.this.onText(text);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[OKX-WS] 连接关闭 code={} reason={}", statusCode, reason);
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[OKX-WS] 连接错误：{}", error.getMessage());
            scheduleReconnect();
        }
    }
}
