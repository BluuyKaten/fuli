package com.fuli.auth.security;

import com.fuli.common.api.config.JwtProperties;
import com.fuli.common.api.security.AuthenticatedUser;
import com.fuli.common.api.security.BearerUserFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** Authenticates the original signed bearer; caller-supplied identity headers are ignored. */
@Component
public class JwtAuthenticationFilter extends BearerUserFilter {
    public JwtAuthenticationFilter(JwtProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = com.fuli.common.api.security.RequestPaths.applicationPath(request);
        return path.equals("/auth/login") || path.equals("/auth/register") || path.equals("/auth/captcha")
                || path.equals("/auth/internal") || path.startsWith("/auth/internal/")
                || path.startsWith("/actuator/health") || path.startsWith("/actuator/info");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        super.doFilterInternal(request, response, (verified, target) -> {
            Long userId = AuthenticatedUser.requireId((HttpServletRequest) verified);
            var authentication = new UsernamePasswordAuthenticationToken("user-" + userId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            authentication.setDetails(userId.toString());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(verified, target);
        });
    }
}
