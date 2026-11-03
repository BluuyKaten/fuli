package com.fuli.common.api.filter;

import com.fuli.common.api.util.TraceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求追踪过滤器：为每个请求落地一个 traceId 到 MDC 与响应头。
 *
 * <p>优先沿用网关透传的 {@code X-Request-Id}，否则本服务生成一个；请求结束后清理 MDC，
 * 避免线程池复用导致的 traceId 串号。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceId.sanitize(request.getHeader(TraceId.HEADER));
        if (!StringUtils.hasText(traceId)) {
            traceId = TraceId.generate();
        }
        MDC.put(TraceId.MDC_KEY, traceId);
        response.setHeader(TraceId.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceId.MDC_KEY);
        }
    }
}
