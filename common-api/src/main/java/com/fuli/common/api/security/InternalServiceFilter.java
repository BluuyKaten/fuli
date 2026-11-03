package com.fuli.common.api.security;

import com.fuli.common.api.config.InternalKeyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared fail-closed inbound authentication for service-only MVC endpoints. */
public class InternalServiceFilter extends OncePerRequestFilter {
    private final InternalKeyProperties properties;
    private final String prefix;

    public InternalServiceFilter(InternalKeyProperties properties, String prefix) {
        this.properties = properties;
        this.prefix = prefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = RequestPaths.applicationPath(request);
        if (path.equals(prefix) || path.startsWith(prefix + "/")) {
            String key = request.getHeader("X-Internal-Key");
            if (key == null || properties.getInternalKey() == null || !MessageDigest.isEqual(
                    key.getBytes(StandardCharsets.UTF_8), properties.getInternalKey().getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"内部接口需要有效凭据\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
