package com.fuli.common.api.security;

import jakarta.servlet.http.HttpServletRequest;

/** Uses the same matrix-parameter-free path that Spring MVC matches. */
public final class RequestPaths {
    private RequestPaths() {}

    public static String applicationPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        return path.replaceAll(";[^/]*", "");
    }
}
