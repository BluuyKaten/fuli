package com.fuli.ai.orchestrator;

import com.fuli.ai.agent.OkxReadOnlyClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P9：Agent 编排层（**规则版，未接 LLM**）。
 *
 * <p>刻意不内嵌任何大模型依赖：模型供应商、API Key、模型选择都需要使用者决定，
 * 硬编码一个反而会引入不可用的默认路径。这里把「自然语言 → 工具选择 → 调用」的
 * 骨架做出来，接 LLM 时只需把 {@link #plan(String)} 换成模型输出。
 *
 * <p><b>只调不需要 JWT 的公共契约</b> {@code /market/bars}。
 * 受保护的 OKX / MCP 端点需要服务账号身份方案（实施计划 §6 第 4 项），未定之前不硬闯鉴权。
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private final RestClient dataClient;

    private final OkxReadOnlyClient okxReadOnly;

    public AgentOrchestrator(@Value("${fuli.data-service-url}") String dataServiceUrl,
                             OkxReadOnlyClient okxReadOnly) {
        this.dataClient = RestClient.builder().baseUrl(dataServiceUrl).build();
        this.okxReadOnly = okxReadOnly;
    }

    public Map<String, Object> ask(String question) {
        String q = question == null ? "" : question.trim();
        Plan plan = plan(q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", q);
        out.put("mode", "RULE_BASED_NO_LLM");
        out.put("tool", plan.tool());
        out.put("symbol", plan.symbol());
        try {
            String body = dataClient.get()
                    .uri(uri -> uri.path("/market/bars")
                            .queryParam("symbol", plan.symbol())
                            .queryParam("period", "1d")
                            .queryParam("limit", 10)
                            .build())
                    .retrieve()
                    .body(String.class);
            out.put("ok", true);
            out.put("data", body);
        } catch (Exception e) {
            log.warn("[AI] data-service 调用失败：{}", e.getMessage());
            out.put("ok", false);
            out.put("error", "data-service 不可用：" + e.getMessage());
        }

        // 加密标的顺带读一次模拟盘挂单：这条链路走服务令牌（scope=orders:read），
        // 拿不到就只是少一条信息，不影响上面的行情结论。
        if (plan.symbol().endsWith(".CRYPTO")) {
            Map<String, Object> account = new LinkedHashMap<>();
            account.put("readOnly", true);
            okxReadOnly.pendingOrdersOrEmpty("SPOT").ifPresentOrElse(
                    data -> account.put("pendingOrders", data),
                    () -> account.put("pendingOrders", "unavailable（服务账号未配置或对端拒绝）"));
            out.put("account", account);
        }

        out.put("next", List.of(
                "接入 LLM 替换 plan()",
                "写建议单用 POST /ai/suggest-order（只落建议，不下单）"));
        return out;
    }

    /** 规则版意图识别；接 LLM 后由模型返回同样的 Plan。 */
    static Plan plan(String question) {
        String lower = question == null ? "" : question.toLowerCase();
        if (lower.contains("btc") || lower.contains("比特币") || lower.contains("加密")
                || lower.contains("eth") || lower.contains("sol")) {
            String symbol = lower.contains("eth") ? "ETH-USDT.CRYPTO"
                    : lower.contains("sol") ? "SOL-USDT.CRYPTO" : "BTC-USDT.CRYPTO";
            return new Plan("market/bars", symbol);
        }
        if (lower.contains("茅台") || lower.contains("600519")) {
            return new Plan("market/bars", "600519.SH");
        }
        return new Plan("market/bars", "BTC-USDT.CRYPTO");
    }

    public record Plan(String tool, String symbol) {
    }
}
