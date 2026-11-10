package com.fuli.data.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * OKX 端点的调用主体（ADR-0010 决策 3/4）。
 *
 * <p>两类主体，权限形状完全不同：
 * <ul>
 *   <li><b>USER</b>：普通用户令牌，带 {@code userId}。读写都看用户级开关
 *       {@code sys_user.okx_trade_enabled}；</li>
 *   <li><b>SERVICE</b>：服务账号令牌（client_credentials，{@code type=SERVICE}），
 *       只有 {@code orders:read}。**永远不能写**——Agent 不下单这条约束在协议层就断掉，
 *       而不是靠「工具列表里没注册写工具」这种约定。</li>
 * </ul>
 *
 * <p>身份只来自服务端 attribute（{@link #ATTRIBUTE}），由
 * {@link OkxAuthenticationFilter} 验签后写入；请求头里的任何身份字段都无效。
 */
public record OkxPrincipal(Type type, Long userId, String clientId, Set<String> scopes) {

    /** 身份属性的键。 */
    public static final String ATTRIBUTE = OkxPrincipal.class.getName();

    public static final String SCOPE_READ = "orders:read";

    public static final String SCOPE_WRITE = "orders:write";

    public enum Type { USER, SERVICE }

    public OkxPrincipal {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        if (type == Type.USER && (userId == null || userId <= 0)) {
            throw new IllegalArgumentException("USER 主体必须有 userId");
        }
        if (type == Type.SERVICE && (clientId == null || clientId.isBlank())) {
            throw new IllegalArgumentException("SERVICE 主体必须有 clientId");
        }
    }

    public static OkxPrincipal user(long userId) {
        return new OkxPrincipal(Type.USER, userId, null, Set.of(SCOPE_READ, SCOPE_WRITE));
    }

    public static OkxPrincipal service(String clientId, Set<String> scopes) {
        return new OkxPrincipal(Type.SERVICE, null, clientId, scopes);
    }

    public boolean isService() {
        return type == Type.SERVICE;
    }

    /** 读：用户恒可；服务账号必须有 {@code orders:read}。 */
    public boolean canRead() {
        return !isService() || scopes.contains(SCOPE_READ);
    }

    /** 写：服务账号永远不行，用户还要再过 {@code okx_trade_enabled}。 */
    public boolean canWrite() {
        return !isService();
    }

    /** 审计里记谁：服务账号记 NULL（列注释已约定），用户记 userId。 */
    public Long auditUserId() {
        return isService() ? null : userId;
    }

    public String display() {
        return isService() ? "service:" + clientId : "user:" + userId;
    }

    /** 从请求里取主体；缺失即 401（过滤器没跑到却有请求进来，属于异常路径）。 */
    public static OkxPrincipal require(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof OkxPrincipal principal) {
            return principal;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
