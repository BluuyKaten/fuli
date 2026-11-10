package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.config.OkxConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * OKX V5 REST 客户端：公共接口免签，私有接口四头签名。
 *
 * <p><b>凭证按调用传入</b>（ADR-0010 决策 1）：私有方法第一个参数就是 {@link OkxCredentials}，
 * 由调用方从当前 JWT 的 userId 解出来。这里**没有**「默认凭证」字段，也没有从
 * {@code OkxConfig} 读凭证的分支——少一个入口，就少一类「配错账户」的事故。
 * HttpClient 是单例字段，连接池与凭证无关，不影响复用。
 *
 * <p>错误处理原则：OKX 的 HTTP 状态码经常是 200，真正的错误在 body 的 {@code code} 字段
 * （如 {@code 51001} 产品不存在、{@code 50113} 签名错）。所以**必须解 body**，
 * 不能只看 statusCode，否则会把失败当成功、拿到空的 data 继续往下走。
 */
@Slf4j
@Component
public class OkxClient {

    /**
     * 自带 mapper，不从容器取。
     *
     * <p>本项目 MVC 侧用的是 fastjson2，容器里没有 {@code ObjectMapper} bean；
     * 为了一个客户端去注册全局 Jackson bean，会反过来改变 MVC 的序列化行为
     * （日期格式、null 处理都会变），不值得。这里只用它做 OKX 回包的树模型解析。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkxConfig config;

    private final HttpClient http;

    public OkxClient(OkxConfig config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getTimeoutMs()));
        InetSocketAddress proxy = proxyAddress(config.getProxy());
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
            log.info("[OKX] 走代理 {}:{}", proxy.getHostString(), proxy.getPort());
        }
        this.http = builder.build();
    }

    /**
     * 解析代理地址，**同时接受** {@code 127.0.0.1:7897} 与 {@code http://127.0.0.1:7897}。
     *
     * <p>实测（Clash Verge）手册里写的是不带协议的 {@code 127.0.0.1:7897}，而
     * {@code URI.create} 会把它当 scheme 解析并抛 "Illegal character in scheme name"，
     * 表现为服务启动即失败——这类「配置格式」问题不该让人翻代码，所以在这里兜住。
     *
     * @return null 表示不配代理（直连）
     */
    public static InetSocketAddress proxyAddress(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim();
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("okx.proxy 不是合法地址：" + raw
                    + "（应形如 http://127.0.0.1:7897 或 127.0.0.1:7897）", e);
        }
        if (uri.getHost() == null || uri.getPort() <= 0) {
            throw new IllegalStateException("okx.proxy 缺少 host:port：" + raw
                    + "（应形如 http://127.0.0.1:7897 或 127.0.0.1:7897）");
        }
        return new InetSocketAddress(uri.getHost(), uri.getPort());
    }

    /** 公共 GET（无凭证）。行情、盘口、标记价等不需要任何账户身份。 */
    public JsonNode publicGet(String path, Map<String, String> query) {
        return execute("GET", path, query, null, null);
    }

    /**
     * 私有 GET（需四头签名；query 会进签名串）。
     *
     * @param credentials 当前用户的凭证，不可为 null —— 传 null 代表调用方没搞清身份，
     *                    这里直接拒绝，而不是回退到某个「系统账户」
     */
    public JsonNode privateGet(OkxCredentials credentials, String path, Map<String, String> query) {
        requireCredentials(credentials);
        return execute("GET", path, query, null, credentials);
    }

    /** 私有 POST。body 序列化成紧凑 JSON，签名用的就是这串字节，不能再 format 一次。 */
    public JsonNode privatePost(OkxCredentials credentials, String path, Object body) {
        requireCredentials(credentials);
        String json;
        try {
            json = JSON.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求体序列化失败: " + e.getMessage(), e);
        }
        return execute("POST", path, null, json, credentials);
    }

    private JsonNode execute(String method, String path, Map<String, String> query,
                             String body, OkxCredentials credentials) {
        String requestPath = path + queryString(query);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(config.getBaseUrl()) + requestPath))
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (credentials != null) {
            String ts = OkxSigner.timestamp();
            builder.header("OK-ACCESS-KEY", credentials.apiKey())
                    .header("OK-ACCESS-SIGN",
                            OkxSigner.sign(credentials.secretKey(), ts, method, requestPath, body))
                    .header("OK-ACCESS-TIMESTAMP", ts)
                    .header("OK-ACCESS-PASSPHRASE", credentials.passphrase());
            // ADR-0009 决策 4：模拟盘是系统常量，无条件携带，没有 if / 没有开关。
            // 模拟盘与实盘同 host，只差这一个头；一旦丢了它请求就落进实盘，务必不要动这段。
            builder.header("x-simulated-trading", "1");
        }

        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body,
                    StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }

        try {
            HttpResponse<String> resp = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = JSON.readTree(resp.body());
            String code = root.path("code").asText("");
            if (!"0".equals(code)) {
                String msg = root.path("msg").asText("");
                log.warn("[OKX] {} {} 失败 code={} msg={}", method, requestPath, code, msg);
                throw new OkxApiException(code, msg, requestPath);
            }
            return root.path("data");
        } catch (OkxApiException e) {
            throw e;
        } catch (Exception e) {
            // 连不通时 e.getMessage() 经常是 null，直接把类名丢掉会得到「...: null」这种没法查的日志
            throw new IllegalStateException("OKX 请求失败 " + method + " " + requestPath
                    + ": " + describe(e), e);
        }
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (msg == null || msg.isBlank()) {
            return name + "（检查网络/代理：okx.proxy，或 OKX 域名是否被解析到 169.254.x.x）";
        }
        return name + ": " + msg;
    }

    private static void requireCredentials(OkxCredentials credentials) {
        if (credentials == null) {
            // 这条异常只可能出现在编码错误上，所以措辞直接指向「你没有从 JWT 取身份」
            throw new IllegalStateException(
                    "私有请求缺少凭证：必须按当前用户从 okx_account 解密取得 OkxCredentials，"
                            + "本服务不存在全局凭证回退");
        }
    }

    private static String queryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&", "?", "");
        query.forEach((k, v) -> {
            if (v == null || v.isEmpty()) {
                return;
            }
            joiner.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        String qs = joiner.toString();
        return "?".equals(qs) ? "" : qs;
    }

    private static String trimTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

    /** OKX 业务错误（HTTP 200 但 code != "0"）。 */
    public static class OkxApiException extends RuntimeException {

        private final String code;

        public OkxApiException(String code, String msg, String path) {
            super("OKX " + code + " " + msg + " (" + path + ")");
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
