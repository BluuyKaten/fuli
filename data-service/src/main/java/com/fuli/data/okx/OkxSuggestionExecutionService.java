package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 建议单「人工确认 → 下单」闭环（阶段 3.3）。
 *
 * <p>与 {@link OkxSuggestionService} 刻意分离：后者只负责<b>记录</b> Agent 的建议、
 * 结构上不引用 {@link OkxTradeService}（保证 Agent 路径永远不能下单）；本服务是
 * <b>人工确认</b>的唯一下单入口，注入 {@link OkxTradeService}，且只被「需要交易权限」
 * 的写端点调用（见 {@code OkxSuggestionController#execute} 的 {@code requireWrite}）。
 *
 * <p>幂等：确认下单用固定 {@code clOrdId = sugg-<id>}，重复执行同一条建议单时，
 * 先被 {@code status != new} 拦截，即便状态被并发绕过，下单侧也有 clOrdId 幂等兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxSuggestionExecutionService {

    private final OkxOrderSuggestionMapper mapper;

    private final OkxTradeService tradeService;

    /**
     * 人工确认某条建议单并转真实模拟盘下单。
     *
     * @param userId       确认人（必须与建议单归属一致）
     * @param suggestionId 建议单 ID
     * @return 下单结果（suggestionId / clOrdId / ordId / status）
     */
    public Map<String, Object> execute(long userId, long suggestionId) {
        OkxOrderSuggestion s = requireOwnPending(userId, suggestionId);

        String clOrdId = "sugg-" + suggestionId;
        JsonNode resp = tradeService.placeOrder(userId, s.getInstId(), s.getSide(), s.getOrdType(),
                s.getSz().toPlainString(), s.getPx() == null ? null : s.getPx().toPlainString(), clOrdId);

        String ordId = null;
        JsonNode first = resp == null ? null : resp.path("data").path(0);
        if (first != null && first.isObject()) {
            ordId = first.path("ordId").asText(null);
        }

        s.setStatus("executed");
        mapper.updateById(s);

        log.info("[OKX-SUGGEST] 人工确认下单 suggestionId={} {} {} {} clOrdId={} ordId={}",
                suggestionId, s.getInstId(), s.getSide(), s.getOrdType(), clOrdId, ordId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", suggestionId);
        result.put("clOrdId", clOrdId);
        result.put("ordId", ordId);
        result.put("status", "executed");
        return result;
    }

    /**
     * 人工否决一条建议单（不改任何订单，只把状态推进到 {@code rejected}）。
     *
     * <p>为什么要有这个入口：没有「拒绝」时，不打算执行的建议只能一直挂在 {@code new}，
     * 列表里的「待确认」数量永远清不掉，看久了就没人再看这个列表了——信号的价值取决于
     * 它能不能被处理干净。
     *
     * <p>与 {@link #execute} 共用 {@link #requireOwnPending}：只有本人的、{@code new} 状态的
     * 建议单可以被处置，避免并发下同一张单被两个人一个确认一个否决。
     */
    public Map<String, Object> reject(long userId, long suggestionId) {
        OkxOrderSuggestion s = requireOwnPending(userId, suggestionId);

        s.setStatus("rejected");
        mapper.updateById(s);

        log.info("[OKX-SUGGEST] 人工否决 suggestionId={} {} {} {}", suggestionId,
                s.getInstId(), s.getSide(), s.getOrdType());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", suggestionId);
        result.put("status", "rejected");
        return result;
    }

    /**
     * 取出「本人的、待处置的」建议单。
     *
     * @throws IllegalArgumentException 不存在 / 不是本人的 / 已处置
     */
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
}
