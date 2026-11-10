package com.fuli.data.okx.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.crypto.CryptoAccountService;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.entity.CryptoFill;
import com.fuli.data.crypto.entity.CryptoPosition;
import com.fuli.data.crypto.mapper.CryptoPositionMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * WS 私有频道解码器（实施计划 P4）：把 {@code orders} / {@code positions} / {@code account}
 * 推送落到 P2 的独立表，是「WS 帧 → 订单状态机」之间唯一的转换层。
 *
 * <p>{@code event} 类控制消息（subscribe / login / error）由 {@code OkxWsClient} 自己处理，
 * 这里直接跳过。非法 JSON 只 WARN 不抛异常——一条坏帧不能打断整个连接。
 *
 * <p><b>account 频道落 {@code crypto_account}</b>（V12）：每个币种一行 + 一行 TOTAL，
 * 靠唯一键 {@code (user_id, ccy, u_time)} 去重。WS 是服务级单连接，用户身份取自
 * {@code okx.ws-owner-user-id}（ADR-0010 决策 5）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxWsMessageDispatcher implements Consumer<String> {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CryptoOrderService orderService;

    private final CryptoPositionMapper positionMapper;

    private final CryptoAccountService accountService;

    private final OkxConfig okxConfig;

    private final OkxWsClient wsClient;

    @PostConstruct
    public void register() {
        wsClient.addHandler(this);
    }

    @Override
    public void accept(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception e) {
            log.warn("[OKX-WS] 忽略无法解析的帧：{}", e.getMessage());
            return;
        }
        if (root == null || !root.isObject() || root.has("event")) {
            return;
        }
        String channel = root.path("arg").path("channel").asText("");
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            return;
        }
        switch (channel) {
            case "orders" -> data.forEach(this::onOrder);
            case "positions" -> data.forEach(this::onPosition);
            case "account" -> data.forEach(this::onAccount);
            default -> log.debug("[OKX-WS] 暂不处理频道 {}（P7/P8 接入）", channel);
        }
    }

    private void onOrder(JsonNode n) {
        String ordId = str(n, "ordId");
        if (ordId == null) {
            return;
        }
        CryptoOrderService.OrderUpdate update = new CryptoOrderService.OrderUpdate(
                ordId, str(n, "state"), dec(n, "accFillSz"), dec(n, "fillSz"),
                dec(n, "avgPx"), dec(n, "fee"), str(n, "feeCcy"), str(n, "tradeId"),
                str(n, "posId"), str(n, "cancelSource"), lng(n, "uTime"), lng(n, "fillTime"));
        boolean applied = orderService.applyOrderUpdate(update);
        if (!applied) {
            return;
        }
        // 有成交 ID 且有本次成交量才落成交明细；tradeId 只在 instId 维度唯一
        String tradeId = str(n, "tradeId");
        if (tradeId != null && dec(n, "fillSz") != null) {
            CryptoFill fill = new CryptoFill();
            fill.setInstId(str(n, "instId"));
            fill.setTradeId(tradeId);
            fill.setOrdId(ordId);
            fill.setClOrdId(str(n, "clOrdId"));
            fill.setSide(str(n, "side"));
            fill.setFillPx(dec(n, "fillPx"));
            fill.setFillSz(dec(n, "fillSz"));
            fill.setFee(dec(n, "fee"));
            fill.setFeeCcy(str(n, "feeCcy"));
            fill.setPosId(str(n, "posId"));
            fill.setFillTime(lng(n, "fillTime"));
            orderService.recordFill(fill);
        }
    }

    /** 余额快照：WS 是单连接，账户身份来自配置（未配置则不写，避免把数据记到别人名下）。 */
    private void onAccount(JsonNode n) {
        long owner = okxConfig.getWsOwnerUserId();
        if (owner <= 0) {
            log.debug("[OKX-WS] account 频道收到推送，但未配置 okx.ws-owner-user-id，跳过落库");
            return;
        }
        accountService.recordSnapshot(owner, n);
    }

    private void onPosition(JsonNode n) {
        String posId = str(n, "posId");
        if (posId == null) {
            return;
        }
        CryptoPosition incoming = new CryptoPosition();
        incoming.setPosId(posId);
        incoming.setInstId(str(n, "instId"));
        incoming.setInstType(str(n, "instType"));
        incoming.setMgnMode(str(n, "mgnMode"));
        incoming.setPosSide(str(n, "posSide"));
        incoming.setPos(dec(n, "pos"));
        incoming.setAvailPos(dec(n, "availPos"));
        incoming.setAvgPx(dec(n, "avgPx"));
        incoming.setUpl(dec(n, "upl"));
        incoming.setUplRatio(dec(n, "uplRatio"));
        incoming.setLever(dec(n, "lever"));
        incoming.setLiqPx(dec(n, "liqPx"));
        incoming.setImr(dec(n, "imr"));
        incoming.setMmr(dec(n, "mmr"));
        incoming.setMgnRatio(dec(n, "mgnRatio"));
        incoming.setTradeId(str(n, "tradeId"));
        incoming.setCTime(lng(n, "cTime"));
        incoming.setUTime(lng(n, "uTime"));

        CryptoPosition existing = positionMapper.selectById(posId);
        if (!orderService.isPositionStale(existing, incoming)) {
            return;  // 定时重推的同一份快照，不写库
        }
        if (existing == null) {
            positionMapper.insert(incoming);
        } else {
            positionMapper.updateById(incoming);
        }
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal dec(JsonNode n, String field) {
        String s = str(n, field);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long lng(JsonNode n, String field) {
        String s = str(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
