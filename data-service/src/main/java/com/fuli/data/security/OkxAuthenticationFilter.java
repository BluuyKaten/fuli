package com.fuli.data.security;

import com.fuli.common.api.security.AuthenticatedUser;
import com.fuli.common.api.security.RequestPaths;
import com.fuli.data.config.OkxAuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * OKX 端点与 MCP 端点的统一身份入口（ADR-0009 决策 8 / ADR-0010 决策 3）。
 *
 * <p>为什么不直接复用 {@code BearerUserFilter}：它要求容器里装配 {@code JwtProperties}，
 * 而 data-service 原先刻意不依赖 JWT（{@code JwtAutoConfiguration} 的条件装配）。
 * 直接要求全局装配会让「只跑 A 股行情同步」的部署因为缺 JWT_SECRET 而整体启动失败。
 * 这里改为按路径生效的 fail-closed：缺密钥时只让 OKX/MCP 端点返回 503，其余功能不受影响。
 *
 * <p>接受两种令牌：
 * <ul>
 *   <li><b>用户令牌</b>（无 {@code type} 声明）：必须有 {@code userId} claim；</li>
 *   <li><b>服务令牌</b>（{@code type=SERVICE}）：必须有 {@code sub}（client_id）、
 *       {@code scope}，且 {@code aud} 必须包含本服务标识。
 *       <b>aud 校验是「禁止 token passthrough」的落地点</b>——给别的受众签的令牌在这里不认。</li>
 * </ul>
 *
 * <p>身份写入服务端 attribute（{@link OkxPrincipal#ATTRIBUTE}），调用方伪造
 * {@code X-User-Id} 无效；网关的 {@code JwtGlobalFilter} 也会剥离外部伪造的身份头，形成双层校验。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OkxAuthenticationFilter extends OncePerRequestFilter {

    private static final String TYPE_SERVICE = "SERVICE";

    private final OkxAuthProperties properties;

    public OkxAuthenticationFilter(OkxAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !OkxAuthPaths.isProtected(RequestPaths.applicationPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (properties.isJwtSecretMissing()) {
            log.error("[OKX-AUTH] JWT_SECRET 未配置或短于 32 字节，拒绝 {} {}",
                    request.getMethod(), RequestPaths.applicationPath(request));
            deny(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "OKX 鉴权未配置：请为 data-service 注入与网关一致的 JWT_SECRET（>=32 字节）");
            return;
        }

        OkxPrincipal principal;
        try {
            principal = authenticate(request);
        } catch (RuntimeException invalidToken) {
            deny(response, HttpServletResponse.SC_UNAUTHORIZED, "令牌无效或未提供");
            return;
        }

        if (!principal.isService()) {
            // 兼容既有代码路径：写的是同一个服务端 attribute，不是请求头
            request.setAttribute(AuthenticatedUser.ATTRIBUTE, principal.userId());
        }
        request.setAttribute(OkxPrincipal.ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    private OkxPrincipal authenticate(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing bearer");
        }
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(
                        properties.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(authorization.substring(7)).getPayload();

        if (claims.getExpiration() == null || !claims.getExpiration().after(new Date())) {
            throw new IllegalArgumentException("Expired");
        }

        String type = claims.get("type", String.class);
        if (TYPE_SERVICE.equalsIgnoreCase(type)) {
            return servicePrincipal(claims);
        }
        Long userId = claims.get("userId", Long.class);
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Invalid identity");
        }
        return OkxPrincipal.user(userId);
    }

    /**
     * 服务令牌：没有 userId，只有 client_id + scope。
     * 受众必须匹配本服务，否则视为「别人家的令牌被我捡到了」。
     */
    private OkxPrincipal servicePrincipal(Claims claims) {
        Set<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(properties.getServiceTokenAudience())) {
            throw new IllegalArgumentException("Wrong audience");
        }
        String clientId = claims.getSubject();
        String scope = claims.get("scope", String.class);
        if (clientId == null || clientId.isBlank() || scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Incomplete service token");
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String part : scope.split("[,\\s]+")) {
            if (!part.isBlank()) {
                scopes.add(part.trim());
            }
        }
        return OkxPrincipal.service(clientId, scopes);
    }

    private static void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // message 是内部固定文案，不含用户输入，无需额外转义
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
