package com.fuli.data.security;

import com.fuli.common.api.security.RequestPaths;
import com.fuli.data.config.OkxAuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /internal/okx/**} 的服务间鉴权（ADR-0010 决策 4）。
 *
 * <p>这条路径**不在** {@link OkxAuthPaths} 里：它面向的是服务账号，不是用户。
 * 两类凭据各管各的，混在一个过滤器里最容易写成「有内部密钥就免 JWT」这种漏洞。
 *
 * <p>密钥复用 {@code okx.auth.internal-key}（同一把 {@code INTERNAL_KEY}），
 * 而不是 {@code fuli.internal-key}：后者在非 local profile 下不一定会被注册，
 * 会让这个端点在没有明显原因的情况下恒 403。为空时一律拒绝（fail-closed）。
 *
 * <p>网关侧还会把任何带 {@code internal} 段的路径直接拒绝，外部调用根本到不了这里。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class OkxInternalKeyFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/internal/okx";

    private final OkxAuthProperties properties;

    public OkxInternalKeyFilter(OkxAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = RequestPaths.applicationPath(request);
        return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String expected = properties.getInternalKey();
        String provided = request.getHeader("X-Internal-Key");
        if (expected == null || expected.isBlank() || provided == null
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"内部接口需要有效凭据\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
