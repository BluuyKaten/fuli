package com.fuli.data.crypto;

import com.fuli.data.crypto.entity.CryptoFill;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.crypto.entity.CryptoPosition;
import com.fuli.data.crypto.mapper.CryptoFillMapper;
import com.fuli.data.crypto.mapper.CryptoOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * OKX 订单域服务（实施计划 P2/P4）。
 *
 * <p><b>写前日志是硬约定</b>：下单必须先 {@link #createPending} 落一行 {@code pending}，
 * 再调用交易所；入口返回后 {@link #markSubmitted}，之后的状态全靠 WS 订单频道
 * {@link #applyOrderUpdate} 推进。断网重启时 {@link #findOrphans} 能捞出发到一半的单。
 *
 * <p>本类不发起任何网络请求，也不碰实盘凭证——所有私有请求仍由 {@code OkxClient}
 * 无条件携带模拟盘头（ADR-0009 决策 4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoOrderService {

    private final CryptoOrderMapper orderMapper;

    private final CryptoFillMapper fillMapper;

    /** 新建一笔待发送订单。参数用 record 收拢，避免一长串同类型 String 传错位置。 */
    public record NewOrder(String clOrdId, String instId, String instType, String side,
                           String ordType, BigDecimal px, BigDecimal sz, String tdMode) {
    }

    /** WS orders 频道的一条推送。字段为 null 表示本次没变，不覆盖库里的值。 */
    public record OrderUpdate(String ordId, String state, BigDecimal accFillSz,
                              BigDecimal fillSz, BigDecimal avgPx, BigDecimal fee, String feeCcy,
                              String tradeId, String posId, String cancelSource,
                              Long uTime, Long fillTime) {
    }

    /**
     * 写前日志 + clOrdId 幂等。
     *
     * <p>OKX 只保证「所有挂单」维度 clOrdId 唯一，历史复用是合法的，所以库里不设 UNIQUE：
     * 仅当最近一条同 clOrdId 的单还处于非终态时才复用它；已终态则允许新建。
     */
    public CryptoOrder createPending(NewOrder req) {
        if (req.clOrdId() == null || req.clOrdId().isBlank()) {
            throw new IllegalArgumentException("clOrdId 不能为空：它是重试去重的唯一凭据");
        }
        if (req.clOrdId().length() > 32) {
            throw new IllegalArgumentException("clOrdId 超过 32 位：" + req.clOrdId());
        }
        CryptoOrder existing = orderMapper.selectLatestByClOrdId(req.clOrdId());
        if (existing != null && !CryptoOrderState.of(existing.getState()).isTerminal()) {
            log.info("[OKX-ORDER] clOrdId 复用命中未完成单，跳过重复下单 id={} state={}",
                    existing.getId(), existing.getState());
            return existing;
        }

        CryptoOrder order = new CryptoOrder();
        order.setClOrdId(req.clOrdId());
        order.setInstId(req.instId());
        order.setInstType(req.instType() == null ? "SPOT" : req.instType());
        order.setSide(req.side());
        order.setOrdType(req.ordType());
        order.setPx(req.px());
        order.setSz(req.sz());
        order.setTdMode(req.tdMode());
        order.setState(CryptoOrderState.PENDING.getCode());
        order.setCreateTime(LocalDateTime.now());
        // 这一步必须在任何网络调用之前完成；调用方拿到返回值后才允许发单。
        orderMapper.insert(order);
        return order;
    }

    /**
     * 交易所入口返回后更新。
     *
     * <p>只有明确的 {@code sCode=0 + ordId} 才能进入 live；缺字段属于结果未知，
     * 必须保留 pending 等待对账。非零且非空的 sCode 表示拒单，此时不会有 WS 推送，
     * 才能在这里定格为 rejected。
     */
    public CryptoOrder markSubmitted(String clOrdId, String ordId, String sCode, String sMsg) {
        CryptoOrder order = orderMapper.selectLatestByClOrdId(clOrdId);
        if (order == null) {
            throw new IllegalStateException("写前日志缺失，拒绝登记未落库的订单：" + clOrdId);
        }
        if (CryptoOrderState.of(order.getState()) != CryptoOrderState.PENDING) {
            // 重试/重复回调：已经登记过就直接返回，不重复改状态
            return order;
        }
        if (sCode == null || sCode.isBlank()) {
            throw new IllegalStateException("OKX 订单响应缺少 sCode，保留 pending 待核实：" + clOrdId);
        }
        boolean accepted = "0".equals(sCode.trim());
        if (accepted && (ordId == null || ordId.isBlank())) {
            throw new IllegalStateException("OKX 订单响应为成功但缺少 ordId，保留 pending 待核实：" + clOrdId);
        }
        order.setOrdId(ordId);
        order.setSCode(sCode.trim());
        order.setSMsg(sMsg);
        order.setState(accepted
                ? CryptoOrderState.LIVE.getCode()
                : CryptoOrderState.REJECTED.getCode());
        order.setUTime(System.currentTimeMillis());
        orderMapper.updateById(order);
        return order;
    }

    /**
     * 应用一条 WS 订单推送。返回 false 表示「已安全忽略」，调用方不要把它当成功。
     *
     * <p>三道防线：未知 ordId 忽略（等 REST 对账补齐）、非法/回退流转拒绝、
     * 比库里更旧的 uTime 忽略（WS 重连后可能补推旧消息）。
     */
    public boolean applyOrderUpdate(OrderUpdate u) {
        CryptoOrder order = orderMapper.selectByOrdId(u.ordId());
        if (order == null) {
            log.warn("[OKX-ORDER] 收到未知订单推送 ordId={}，等待 orders-pending 对账补齐", u.ordId());
            return false;
        }
        CryptoOrderState current = CryptoOrderState.of(order.getState());
        CryptoOrderState next;
        try {
            next = CryptoOrderState.of(u.state());
        } catch (IllegalArgumentException e) {
            log.warn("[OKX-ORDER] 忽略未知状态 ordId={} state={}", u.ordId(), u.state());
            return false;
        }
        if (!current.canTransitionTo(next)) {
            log.warn("[OKX-ORDER] 拒绝非法状态流转 ordId={} {} -> {}", u.ordId(), current, next);
            return false;
        }
        if (u.uTime() != null && order.getUTime() != null && u.uTime() < order.getUTime()) {
            log.debug("[OKX-ORDER] 忽略过期推送 ordId={} uTime={} < {}", u.ordId(), u.uTime(), order.getUTime());
            return false;
        }

        order.setState(next.getCode());
        setIfNotNull(u.accFillSz(), order::setAccFillSz);
        setIfNotNull(u.fillSz(), order::setFillSz);
        setIfNotNull(u.avgPx(), order::setAvgPx);
        setIfNotNull(u.fee(), order::setFee);
        setIfNotNull(u.feeCcy(), order::setFeeCcy);
        setIfNotNull(u.tradeId(), order::setTradeId);
        setIfNotNull(u.posId(), order::setPosId);
        setIfNotNull(u.cancelSource(), order::setCancelSource);
        setIfNotNull(u.fillTime(), order::setFillTime);
        setIfNotNull(u.uTime(), order::setUTime);
        orderMapper.updateById(order);
        return true;
    }

    /** 断网重启后的孤儿单：已落库但未发到交易所。 */
    public List<CryptoOrder> findOrphans() {
        return orderMapper.selectPending();
    }

    /** 按交易所订单 ID 查本地单；撤单前用来确认这不是一笔来路不明的单。 */
    public CryptoOrder findByOrdId(String ordId) {
        return orderMapper.selectByOrdId(ordId);
    }

    /** 本地未完成单，供与 {@code orders-pending} 做对账。 */
    public List<CryptoOrder> findOpenOrders(int limit) {
        return orderMapper.selectOpenOrders(limit);
    }

    /** 落成交明细；复合主键冲突视为已处理过，返回 false 而不是抛异常。 */
    public boolean recordFill(CryptoFill fill) {
        try {
            fillMapper.insert(fill);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("[OKX-ORDER] 成交已存在 instId={} tradeId={}", fill.getInstId(), fill.getTradeId());
            return false;
        }
    }

    /**
     * 持仓对账：判断 incoming 是否比 existing 新，需要落库。
     *
     * <p>不能只比 tradeId：强平/ADL 不更新 tradeId，且多个持仓变化可能聚合成一条推送。
     * 因此 pos 变化、uTime 变新、tradeId 变大，三者任一成立即认为有变化（okx.txt 对账章节）。
     */
    public boolean isPositionStale(CryptoPosition existing, CryptoPosition incoming) {
        if (existing == null) {
            return true;
        }
        if (incoming == null) {
            return false;
        }
        if (!Objects.equals(existing.getPos(), incoming.getPos())) {
            return true;
        }
        Long oldTime = existing.getUTime();
        Long newTime = incoming.getUTime();
        if (newTime != null && (oldTime == null || newTime > oldTime)) {
            return true;
        }
        return compareTradeId(incoming.getTradeId(), existing.getTradeId()) > 0;
    }

    /** tradeId 是数字字符串；强平/ADL 为负数。解析不了时退化为字符串比较，不抛异常。 */
    static int compareTradeId(String incoming, String existing) {
        if (incoming == null) {
            return 0;
        }
        if (existing == null) {
            return 1;
        }
        try {
            return Long.compare(Long.parseLong(incoming), Long.parseLong(existing));
        } catch (NumberFormatException e) {
            return incoming.compareTo(existing);
        }
    }

    private static <T> void setIfNotNull(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
