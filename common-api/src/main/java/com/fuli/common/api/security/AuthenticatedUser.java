package com.fuli.common.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Identity is a server-side attribute, never a caller-supplied user header. */
public final class AuthenticatedUser {
    static final String ATTRIBUTE = AuthenticatedUser.class.getName() + ".userId";

    private AuthenticatedUser() {}

    public static Long requireId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof Long userId && userId > 0) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
