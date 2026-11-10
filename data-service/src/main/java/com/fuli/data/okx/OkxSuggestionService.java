package com.fuli.data.okx;

import com.fuli.data.okx.entity.OkxOrderSuggestion;
import com.fuli.data.okx.mapper.OkxOrderSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Agent 下单建议的落库与查询（ADR-0010 决策 4）。
 *
 * <p><b>唯一职责是记录</b>：这里不注入 {@code OkxTradeService}、不引用 {@code OkxClient}，
 * 因此在结构上就不可能「顺手下一单」。要下单只能由人工在 OKX 侧或前端走
 * {@code /data/okx/trade/order}（那需要交易权限，Agent 没有）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxSuggestionService {

    /** 本期只开放现货现金账户，与 OkxTradeService 的口径保持一致。 */
    private static final String TD_MODE = "cash";

    private static final Set<String> SIDES = Set.of("buy", "sell");

    private static final Set<String> ORD_TYPES = Set.of("limit", "market");

    private static final int REASON_MAX = 512;

    private final OkxOrderSuggestionMapper mapper;

    public OkxSuggestionView submit(OkxSuggestionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.userId() == null || request.userId() <= 0) {
            throw new IllegalArgumentException("缺少 userId（内部接口由调用方传入已验证的身份）");
        }
        String instId = OkxSymbols.toInstId(request.instId());
        String side = request.side() == null ? "" : request.side().trim().toLowerCase();
        if (!SIDES.contains(side)) {
            throw new IllegalArgumentException("side 只能是 buy/sell");
        }
        String ordType = request.ordType() == null || request.ordType().isBlank()
                ? "limit" : request.ordType().trim().toLowerCase();
        if (!ORD_TYPES.contains(ordType)) {
            throw new IllegalArgumentException("ordType 只能是 limit/market");
        }
        BigDecimal sz = request.sz();
        if (sz == null || sz.signum() <= 0) {
            throw new IllegalArgumentException("sz 必须是正数");
        }
        BigDecimal px = request.px();
        if ("limit".equals(ordType) && (px == null || px.signum() <= 0)) {
            throw new IllegalArgumentException("限价建议必须给 px");
        }
        if ("market".equals(ordType)) {
            px = null;
        }
        BigDecimal confidence = request.confidence();
        if (confidence != null && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("confidence 必须在 0~1 之间");
        }

        OkxOrderSuggestion row = new OkxOrderSuggestion();
        row.setUserId(request.userId());
        row.setInstId(instId);
        row.setSide(side);
        row.setOrdType(ordType);
        row.setSz(sz);
        row.setPx(px);
        row.setTdMode(TD_MODE);
        row.setReason(truncate(request.reason()));
        row.setConfidence(confidence);
        row.setStatus("new");
        row.setSource("agent");
        mapper.insert(row);

        log.info("[OKX-SUGGEST] 记录建议 userId={} {} {} {} sz={} px={}（未下单）",
                row.getUserId(), instId, side, ordType, sz, px);
        return toView(row);
    }

    public List<OkxSuggestionView> recent(long userId, int limit) {
        int size = limit <= 0 ? 20 : Math.min(limit, 100);
        List<OkxSuggestionView> views = new ArrayList<>();
        for (OkxOrderSuggestion row : mapper.selectRecentByUser(userId, size)) {
            views.add(toView(row));
        }
        return views;
    }

    private static OkxSuggestionView toView(OkxOrderSuggestion row) {
        return new OkxSuggestionView(row.getId(), row.getInstId(), row.getSide(), row.getOrdType(),
                row.getSz(), row.getPx(), row.getTdMode(), row.getReason(), row.getConfidence(),
                row.getStatus(), row.getSource(), row.getCreateTime());
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > REASON_MAX ? cleaned.substring(0, REASON_MAX - 3) + "..." : cleaned;
    }
}
