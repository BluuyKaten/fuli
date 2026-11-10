package com.fuli.ai.controller;

import com.fuli.ai.agent.OkxReadOnlyClient;
import com.fuli.ai.agent.SuggestionClient;
import com.fuli.ai.orchestrator.AgentOrchestrator;
import com.fuli.common.api.Result;
import com.fuli.common.api.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 入口。网关路由 /api/ai/**（stripPrefix 后落到 /ai/**），
 * 服务内再由 {@code AiAuthenticationFilter} 自己验一遍令牌（不信任 X-User-Id）。
 */
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AgentController {

    private final AgentOrchestrator orchestrator;

    private final OkxReadOnlyClient okxReadOnly;

    private final SuggestionClient suggestionClient;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("mode", "RULE_BASED_NO_LLM");
        body.put("readOnly", true);
        return Result.success(body);
    }

    /** 问答：规则编排 + 公共行情；服务令牌可用时附带模拟盘挂单。 */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        String question = body == null ? "" : body.getOrDefault("question", "");
        if (question.isBlank()) {
            return Result.error(400, "question 不能为空");
        }
        return Result.success(orchestrator.ask(question));
    }

    /**
     * 生成下单建议（ADR-0010 决策 4）。
     *
     * <p><b>Agent 不下单</b>：这里只把建议写进 {@code okx_order_suggestion}，
     * 不调用 {@code /data/okx/trade/order}。要真的成交，必须由本人带着交易权限手动下单。
     *
     * <p>body: {@code symbol/instId, side, ordType, sz, px, reason}
     */
    @PostMapping("/suggest-order")
    public Result<Map<String, Object>> suggestOrder(HttpServletRequest request,
                                                    @RequestBody Map<String, String> body) {
        Long userId = AuthenticatedUser.requireId(request);
        String instId = firstNonBlank(body, "instId", "symbol");
        if (instId == null) {
            return Result.error(400, "缺少参数：instId（或 symbol）");
        }
        String side = body.getOrDefault("side", "");
        String ordType = body.getOrDefault("ordType", "limit");
        String sz = body.getOrDefault("sz", "");
        if (side.isBlank() || sz.isBlank()) {
            return Result.error(400, "side 与 sz 必填");
        }
        try {
            String response = suggestionClient.submit(userId, instId, side, ordType, sz,
                    body.get("px"), body.get("reason"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("submitted", true);
            out.put("note", "仅记录建议，不会下单；成交与否由人工决定");
            out.put("result", response);
            return Result.success(out);
        } catch (Exception e) {
            // 写建议失败通常是配置问题（内部密钥/对端不可用），如实回给调用方而不是假装成功
            return Result.error(502, "建议单未写入：" + safe(e.getMessage()));
        }
    }

    /**
     * 只读账户视图（挂单）。演示「服务令牌 → data-service 受保护端点」这条链路，
     * 同时也是运维自检：返回 {@code ok=false} 说明服务账号没配好。
     */
    @GetMapping("/okx/pending-orders")
    public Result<Map<String, Object>> pendingOrders() {
        Map<String, Object> out = new LinkedHashMap<>();
        OkxReadOnlyClient.ReadResult result = okxReadOnly.pendingOrders("SPOT");
        out.put("ok", result.ok());
        out.put("source", "service-token:orders:read");
        if (result.ok()) {
            out.put("data", result.body());
        } else {
            // 区分「令牌问题」与「业务问题」（例如该账号还没录 OKX 凭证）
            out.put("reason", result.reason());
        }
        return Result.success(out);
    }


    private static String firstNonBlank(Map<String, String> body, String... keys) {
        for (String key : keys) {
            String value = body == null ? null : body.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String safe(String raw) {
        if (raw == null) {
            return "unknown";
        }
        String cleaned = raw.replaceAll("[\\r\\n\\t]", " ");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) + "..." : cleaned;
    }
}
