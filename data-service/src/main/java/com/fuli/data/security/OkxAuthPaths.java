package com.fuli.data.security;

/**
 * 需要鉴权的路径清单，集中在一处，避免过滤器与控制器各写一份而出现漏网之鱼。
 *
 * <p>MCP 端点目前尚未实现（ADR-0009 决策 2），但两个候选路径已提前纳入保护：
 * 将来一旦注册 MCP server，它不会以「默认未鉴权 JSON-RPC」的形态裸奔。
 */
public final class OkxAuthPaths {

    private static final String OKX_PREFIX = "/data/okx";

    /** ADR-0009 约定的网关路由落地后的服务内路径，以及 Spring AI MCP server 的默认端点。 */
    private static final String[] MCP_PREFIXES = {"/okx/mcp", "/mcp"};

    private OkxAuthPaths() {}

    public static boolean isProtected(String path) {
        if (path == null) {
            return false;
        }
        if (path.equals(OKX_PREFIX) || path.startsWith(OKX_PREFIX + "/")) {
            return true;
        }
        for (String prefix : MCP_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
