package com.fuli.data.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxTradeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P5：把 OKX 只读能力暴露成 MCP 工具（Spring AI 2.0 的 {@code @McpTool}）。
 *
 * <p><b>只注册只读工具</b>。写工具一律不在这里出现：MCP 调用不经过 {@code OkxController}，
 * 绕过了 {@code OkxAuthz} 的写 scope 与写前日志。写操作必须走
 * {@code /data/okx/trade/**} 或 {@code /data/okx/capability/**}，由 HTTP 鉴权链路兜住。
 *
 * <p><b>私有工具的身份（ADR-0010 决策 1）</b>：MCP 工具没有 HTTP 请求上下文，拿不到「当前 JWT」。
 * 因此余额/持仓/挂单这三个工具使用显式配置的账户身份 {@code okx.mcp-owner-user-id}，
 * 再去 {@code okx_account} 解密该用户的凭证。没配置（0）时工具直接返回错误说明，
 * <b>不会回退到任何全局凭证</b>——那正是本次要消除的东西。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxMcpTools {

    private final OkxClient client;

    private final OkxTradeService tradeService;

    private final OkxConfig okxConfig;

    @McpTool(name = "market_get_ticker", description = "查询单个交易对的最新行情（最新价、24h 成交量、买一卖一）")
    public String getTicker(@McpToolParam(description = "OKX instId，如 BTC-USDT", required = true) String instId) {
        return read(() -> client.publicGet("/api/v5/market/ticker", Map.of("instId", instId)));
    }

    @McpTool(name = "market_get_candles", description = "查询 K 线（最近 300 根，返回最新一页）")
    public String getCandles(
            @McpToolParam(description = "OKX instId，如 BTC-USDT", required = true) String instId,
            @McpToolParam(description = "K 线周期，如 1m/1H/1Dutc，默认 1Dutc", required = false) String bar,
            @McpToolParam(description = "返回根数，默认 100，最大 300", required = false) String limit) {
        return read(() -> {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("instId", instId);
            q.put("bar", (bar == null || bar.isBlank()) ? "1Dutc" : bar);
            q.put("limit", (limit == null || limit.isBlank()) ? "100" : limit);
            return client.publicGet("/api/v5/market/candles", q);
        });
    }

    @McpTool(name = "market_get_orderbook", description = "查询盘口深度")
    public String getOrderbook(
            @McpToolParam(description = "OKX instId", required = true) String instId,
            @McpToolParam(description = "深度档数，默认 5", required = false) String sz) {
        return read(() -> {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("instId", instId);
            q.put("sz", (sz == null || sz.isBlank()) ? "5" : sz);
            return client.publicGet("/api/v5/market/books", q);
        });
    }

    @McpTool(name = "market_get_funding_rate", description = "查询永续合约当前资金费率")
    public String getFundingRate(@McpToolParam(description = "永续 instId，如 BTC-USDT-SWAP", required = true) String instId) {
        return read(() -> client.publicGet("/api/v5/public/funding-rate", Map.of("instId", instId)));
    }

    @McpTool(name = "market_get_open_interest", description = "查询合约持仓量")
    public String getOpenInterest(@McpToolParam(description = "instId 或 instType", required = true) String instId) {
        return read(() -> client.publicGet("/api/v5/public/open-interest", Map.of("instId", instId)));
    }

    @McpTool(name = "market_get_mark_price", description = "查询衍生品标记价格")
    public String getMarkPrice(@McpToolParam(description = "instId", required = true) String instId) {
        return read(() -> client.publicGet("/api/v5/public/mark-price", Map.of("instId", instId)));
    }

    @McpTool(name = "account_get_balance", description = "查询模拟盘交易账户余额")
    public String getBalance(@McpToolParam(description = "币种，如 USDT；留空返回全部", required = false) String ccy) {
        return read(() -> tradeService.balance(ownerUserId(), ccy));
    }

    @McpTool(name = "account_get_positions", description = "查询模拟盘持仓")
    public String getPositions(@McpToolParam(description = "产品类型，默认 SWAP", required = false) String instType) {
        return read(() -> tradeService.positions(ownerUserId(), instType));
    }

    @McpTool(name = "trade_get_pending_orders", description = "查询模拟盘未完成订单")
    public String getPendingOrders(@McpToolParam(description = "产品类型，默认 SPOT", required = false) String instType) {
        return read(() -> tradeService.pendingOrders(ownerUserId(), instType));
    }

    /**
     * MCP 私有工具使用的账户身份。
     *
     * @throws IllegalStateException 未配置或该用户没录凭证（由 {@link #read} 转成结构化错误）
     */
    private long ownerUserId() {
        long owner = okxConfig.getMcpOwnerUserId();
        if (owner <= 0) {
            throw new IllegalStateException(
                    "MCP 私有工具未配置账户身份：请设置 okx.mcp-owner-user-id 为某个已在"
                            + "「系统设置 → OKX 账户」录入凭证的用户 ID（MCP 无请求上下文，不能靠 JWT 推断）");
        }
        return owner;
    }

    private static String read(java.util.concurrent.Callable<JsonNode> call) {
        try {
            JsonNode data = call.call();
            return "{\"ok\":true,\"data\":" + (data == null ? "null" : data.toString()) + "}";
        } catch (Exception e) {
            log.warn("[OKX-MCP] 工具调用失败：{}", e.getMessage());
            return "{\"ok\":false,\"error\":\"" + sanitize(e.getMessage()) + "\"}";
        }
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "unknown error";
        }
        return raw.replace("\"", "'").replaceAll("[\\r\\n\\t]", " ");
    }
}
