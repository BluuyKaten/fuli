package com.fuli.data.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.config.OkxAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户级 OKX 交易权限判定（ADR-0010 决策 2）。
 *
 * <p>权威数据在 {@code auth_db.sys_user.okx_trade_enabled}，归属 auth-service；
 * data-service 通过内部接口读取，**不跨库直连**——跨库读会把两个服务的部署绑死，
 * 也让「谁拥有这份数据」变得含混。
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>fail-closed</b>：auth-service 不可达、返回非 200、返回体解析不出来，
 *       一律判「无权限」。交易权限查询失败时放行，等于把可用性故障升级成资金风险。</li>
 *   <li><b>短缓存</b>：默认 30 秒。每次写操作都打一次 auth-service 会增加一处单点，
 *       缓存把影响面压到 1/30；代价是收回权限最迟 30 秒生效（撤销令牌可立即生效）。</li>
 *   <li><b>只用内部密钥</b>：这是服务对服务的调用，不带用户令牌，也不允许把用户令牌透传过来。</li>
 * </ol>
 */
@Slf4j
@Component
public class OkxTradePermissionClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkxAuthProperties properties;

    private final RestClient rest;

    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    private final AtomicBoolean warnedMissingKey = new AtomicBoolean(false);

    private record Cached(boolean allowed, long expiresAtMillis) {
    }

    public OkxTradePermissionClient(OkxAuthProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 权限查询挂在交易链路上，必须有自己的超时；默认无超时会把下单请求一起拖死
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.rest = RestClient.builder()
                .baseUrl(properties.getPermissionBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 该用户是否被授予 OKX 交易权限。任何异常路径都返回 false。
     */
    public boolean isTradeEnabled(long userId) {
        if (userId <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        Cached hit = cache.get(userId);
        if (hit != null && hit.expiresAtMillis() > now) {
            return hit.allowed();
        }
        boolean allowed = query(userId);
        long ttlMillis = Math.max(0, properties.getPermissionCacheSeconds()) * 1000L;
        if (ttlMillis > 0) {
            cache.put(userId, new Cached(allowed, now + ttlMillis));
        }
        return allowed;
    }

    /** 授权变更后主动失效（由运维脚本或测试调用）。 */
    public void invalidate(long userId) {
        cache.remove(userId);
    }

    private boolean query(long userId) {
        if (properties.getInternalKey() == null || properties.getInternalKey().isBlank()) {
            if (warnedMissingKey.compareAndSet(false, true)) {
                log.error("[OKX-AUTH] okx.auth.internal-key 未配置，所有写操作将被拒绝。"
                        + "请注入与 auth-service 的 fuli.internal-key 一致的 INTERNAL_KEY。");
            }
            return false;
        }
        try {
            String body = rest.get()
                    .uri(uri -> uri.path("/auth/internal/okxTradeEnabled")
                            .queryParam("userId", userId).build())
                    .header("X-Internal-Key", properties.getInternalKey())
                    .retrieve()
                    .body(String.class);
            JsonNode root = JSON.readTree(body == null ? "{}" : body);
            if (root.path("code").asInt(-1) != 200) {
                log.warn("[OKX-AUTH] 查询 okx_trade_enabled 失败 userId={} code={} msg={}",
                        userId, root.path("code").asText(), root.path("message").asText());
                return false;
            }
            boolean allowed = root.path("data").asBoolean(false);
            log.debug("[OKX-AUTH] okx_trade_enabled userId={} allowed={}", userId, allowed);
            return allowed;
        } catch (Exception e) {
            // 不缓存失败结果：auth-service 恢复后应立刻可用，而不是再等一个 TTL
            log.warn("[OKX-AUTH] 查询 okx_trade_enabled 异常，按无权限处理 userId={}：{}",
                    userId, e.getMessage());
            return false;
        }
    }
}
