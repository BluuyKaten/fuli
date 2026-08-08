package com.fuli.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 认证过滤器（auth-service 端）。
 *
 * <p>信任网关已校验的 {@code X-User-Id} / {@code X-Username} 请求头，
 * 不再重复解析 JWT，消除双密钥漂移风险。
 *
 * <p>内部调用（{@code /auth/internal/**}）由 {@link InternalKeyFilter} 把关，不走此过滤器。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USERNAME_HEADER = "X-Username";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String userIdHeader = request.getHeader(USER_ID_HEADER);
        if (StringUtils.hasText(userIdHeader) && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String username = request.getHeader(USERNAME_HEADER);
                if (!StringUtils.hasText(username)) {
                    username = "user-" + userIdHeader;
                }
                List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                        new SimpleGrantedAuthority("ROLE_USER"));
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                authentication.setDetails(userIdHeader);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.error("Cannot set user authentication from X-User-Id: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
