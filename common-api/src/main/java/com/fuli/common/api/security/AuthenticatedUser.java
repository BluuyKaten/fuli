package com.fuli.common.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Identity is a server-side attribute, never a caller-supplied user header. */
public final class AuthenticatedUser {
    /**
     * 身份属性的键。服务侧的鉴权过滤器写入、{@link #requireId} 读取，
     * 因此需要对不同包中的过滤器可见；但值本身绝不接受调用方通过请求头提供。
     */
    public static final String ATTRIBUTE = AuthenticatedUser.class.getName() + ".userId";

    private AuthenticatedUser() {}

    public static Long requireId(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof Long userId && userId > 0) {
            return userId;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
