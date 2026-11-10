package com.fuli.data.security;

import com.fuli.data.okx.entity.OkxAuditLog;
import com.fuli.data.okx.mapper.OkxAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审计双写：结构化日志 + {@code okx_audit_log} 落表（ADR-0010 决策 3）。
 *
 * <p><b>落表失败绝不影响主流程</b>：这里是「尽力而为」语义。
 * 审计是事后追责手段，不是业务正确性的前提——因为数据库抖动就让一笔已经发到交易所的订单
 * 返回 500，调用方会重试，那才会真的产生重复下单。所以异常在这里被吞掉，只留 WARN，
 * 并靠日志里的 {@code [OKX-AUDIT]} 行兜底（日志采集链路是另一条独立通道）。
 *
 * <p><b>写入前统一清洗</b>：换行/制表符替换成下划线（防日志注入与多行污染），
 * 超长按列宽截断（否则严格模式下 insert 直接失败，等于把审计丢了）。
 * 凭证与完整请求体永远不进这里——调用方只传白名单字段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxAuditService {

    /** 与 DDL 列宽对齐，避免严格模式下的截断异常。 */
    private static final int ACTION_MAX = 64;

    private static final int SCOPE_MAX = 32;

    private static final int OUTCOME_MAX = 16;

    private static final int INST_ID_MAX = 64;

    private static final int SYMBOL_MAX = 64;

    private static final int SIDE_MAX = 8;

    private static final int ORD_ID_MAX = 64;

    private static final int CL_ORD_ID_MAX = 32;

    private static final int DETAIL_MAX = 512;

    private static final int TRACE_ID_MAX = 64;

    private final OkxAuditLogMapper mapper;

    /** 一条审计事件。字段与表列一一对应，多余的参数不要往里加。 */
    public record Event(String action, String scope, String outcome, Long userId,
                        String instId, String symbol, String side, BigDecimal sz, BigDecimal px,
                        String ordId, String clOrdId, String detail) {

        public static Event of(String action, String scope, String outcome, Long userId, String detail) {
            return new Event(action, scope, outcome, userId, null, null, null, null, null,
                    null, null, detail);
        }
    }

    /** 双写入口。任何情况下都不向外抛异常。 */
    public void record(Event event) {
        if (event == null) {
            return;
        }
        String traceId = traceId();
        writeLog(event, traceId);
        persist(event, traceId);
    }

    private void writeLog(Event e, String traceId) {
        String line = "action=" + e.action()
                + " userId=" + (e.userId() == null ? "service" : e.userId())
                + " scope=" + e.scope()
                + " outcome=" + e.outcome();
        if (e.instId() != null) {
            line += " instId=" + e.instId();
        }
        if (e.ordId() != null) {
            line += " ordId=" + e.ordId();
        }
        if (e.detail() != null && !e.detail().isBlank()) {
            line += " detail=" + e.detail();
        }
        if (traceId != null) {
            line += " traceId=" + traceId;
        }
        // 前缀固定，方便日志平台按 [OKX-AUDIT] 建索引
        log.info("[OKX-AUDIT] {}", clean(line, 900));
    }

    private void persist(Event e, String traceId) {
        try {
            OkxAuditLog row = new OkxAuditLog();
            row.setUserId(e.userId());
            row.setAction(truncate(clean(e.action(), ACTION_MAX), ACTION_MAX));
            row.setScope(truncate(clean(e.scope(), SCOPE_MAX), SCOPE_MAX));
            row.setOutcome(truncate(clean(e.outcome(), OUTCOME_MAX), OUTCOME_MAX));
            row.setInstId(truncate(clean(e.instId(), INST_ID_MAX), INST_ID_MAX));
            row.setSymbol(truncate(clean(e.symbol(), SYMBOL_MAX), SYMBOL_MAX));
            row.setSide(truncate(clean(e.side(), SIDE_MAX), SIDE_MAX));
            row.setSz(e.sz());
            row.setPx(e.px());
            row.setOrdId(truncate(clean(e.ordId(), ORD_ID_MAX), ORD_ID_MAX));
            row.setClOrdId(truncate(clean(e.clOrdId(), CL_ORD_ID_MAX), CL_ORD_ID_MAX));
            row.setDetail(truncate(clean(e.detail(), DETAIL_MAX), DETAIL_MAX));
            row.setTraceId(truncate(clean(traceId, TRACE_ID_MAX), TRACE_ID_MAX));
            row.setCreateTime(LocalDateTime.now());
            mapper.insert(row);
        } catch (Exception persistFailure) {
            // 刻意吞掉：审计失败不能把业务打成 500，否则调用方重试会造成重复下单
            log.warn("[OKX-AUDIT] 落表失败（已忽略，日志仍有记录）：{}", persistFailure.getMessage());
        }
    }

    /** 换行/制表符会污染结构化日志，也会让表里出现肉眼看不见的多行值。 */
    static String clean(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.replaceAll("[\\r\\n\\t]", "_");
        return truncate(value, max);
    }

    static String truncate(String raw, int max) {
        if (raw == null || raw.length() <= max) {
            return raw;
        }
        return raw.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String traceId() {
        String fromMdc = MDC.get(com.fuli.common.api.util.TraceId.MDC_KEY);
        return fromMdc == null || fromMdc.isBlank() ? null : fromMdc;
    }
}
