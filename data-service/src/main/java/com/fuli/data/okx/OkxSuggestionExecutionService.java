package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import com.fuli.data.risk.RiskControlException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 建议单人工处置：先原子抢占，再执行网络请求，避免并发重复下单。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxSuggestionExecutionService {

    private final OkxOrderSuggestionMapper mapper;
    private final OkxTradeService tradeService;

    public Map<String, Object> execute(long userId, long suggestionId) {
        tradeService.assertTradingEnabled();
        OkxOrderSuggestion s = requireOwnPending(userId, suggestionId);
        if (mapper.updateStatusIf(suggestionId, userId, "new", "executing") != 1) {
            throw new IllegalArgumentException("建议单已被其他请求处理：" + suggestionId);
        }

        String clOrdId = "sugg-" + suggestionId;
        try {
            JsonNode resp = tradeService.placeOrder(userId, s.getInstId(), s.getSide(), s.getOrdType(),
                    s.getSz().toPlainString(), s.getPx() == null ? null : s.getPx().toPlainString(), clOrdId);
            OkxOrderResponse result = OkxOrderResponse.first(resp);
            if (!result.accepted()) {
                throw new IllegalStateException("OKX 下单结果待核实：" + clOrdId);
            }
            if (mapper.updateStatusIf(suggestionId, userId, "executing", "executed") != 1) {
                throw new IllegalStateException("建议单状态更新失败，订单已受理：" + suggestionId);
            }
            return result(suggestionId, clOrdId, result.ordId(), "executed");
        } catch (OkxCredentialException | RiskControlException | OkxClient.OkxApiException | IllegalArgumentException e) {
            resetToNew(userId, suggestionId);
            throw e;
        }
    }

    private void resetToNew(long userId, long suggestionId) {
        mapper.updateStatusIf(suggestionId, userId, "executing", "new");
    }

    public Map<String, Object> reject(long userId, long suggestionId) {
        requireOwnPending(userId, suggestionId);
        if (mapper.updateStatusIf(suggestionId, userId, "new", "rejected") != 1) {
            throw new IllegalArgumentException("建议单已被其他请求处理：" + suggestionId);
        }
        return result(suggestionId, null, null, "rejected");
    }

    private OkxOrderSuggestion requireOwnPending(long userId, long suggestionId) {
        OkxOrderSuggestion s = mapper.selectById(suggestionId);
        if (s == null) {
            throw new IllegalArgumentException("建议单不存在：" + suggestionId);
        }
        if (s.getUserId() == null || s.getUserId() != userId) {
            throw new IllegalArgumentException("无权处置他人的建议单");
        }
        if (!"new".equals(s.getStatus())) {
            throw new IllegalArgumentException("建议单已处理（状态 " + s.getStatus() + "），不可重复处置");
        }
        return s;
    }

    private static Map<String, Object> result(long suggestionId, String clOrdId, String ordId, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", suggestionId);
        result.put("clOrdId", clOrdId);
        result.put("ordId", ordId);
        result.put("status", status);
        return result;
    }
}
