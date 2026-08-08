package com.fuli.auth.security;

import com.fuli.common.api.config.InternalKeyProperties;
import com.fuli.common.api.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内部接口调用鉴权过滤器
 * 拦截 /auth/internal/** 请求，验证 X-Internal-Key 请求头。
 *
 * <p>密钥统一由 {@link InternalKeyProperties} 注入，启动时会校验强度，
 * 与 Feign 拦截器端共用同一密钥。
 */
@Slf4j
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private static final String INTERNAL_HEADER = "X-Internal-Key";

    private final InternalKeyProperties internalKeyProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** @param internalKeyProperties 统一内部服务密钥配置（来自 common-api） */
    public InternalKeyFilter(InternalKeyProperties internalKeyProperties) {
        this.internalKeyProperties = internalKeyProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (path.startsWith("/auth/internal/")) {
            String providedKey = request.getHeader(INTERNAL_HEADER);
            if (!StringUtils.hasText(providedKey)
                    || !providedKey.equals(internalKeyProperties.getInternalKey())) {
                log.warn("Internal API access denied: path={}, providedKey={}", path, providedKey);
                writeForbidden(response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Result<?> result = Result.forbidden("内部接口需要有效的 X-Internal-Key");
        byte[] bytes = objectMapper.writeValueAsBytes(result);
        response.getOutputStream().write(bytes);
    }
}
