package com.fuli.data.crypto;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * OKX 订单状态机（ADR-0009 / 实施计划 P4，状态口径见 okx.txt「可能的订单状态」）。
 *
 * <p>{@link #PENDING} 是我们自己加的：表示「已落库、尚未发网络」的写前日志行，
 * 交易所不存在这个状态。有了它，断网重启才能识别孤儿单。
 *
 * <p>终态（filled / canceled / rejected）一旦到达就<b>完全冻结</b>：
 * 既不接受回退，也不接受同状态重推，避免 WS 定时重推把已经定格的单子改脏。
 */
public enum CryptoOrderState {

    PENDING("pending"),
    LIVE("live"),
    PARTIALLY_FILLED("partially_filled"),
    FILLED("filled"),
    CANCELED("canceled"),
    REJECTED("rejected");

    private final String code;

    CryptoOrderState(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 允许的流转；不含 same（same 由 {@link #canTransitionTo} 单独处理）。 */
    private static final Map<CryptoOrderState, EnumSet<CryptoOrderState>> ALLOWED =
            new EnumMap<>(CryptoOrderState.class);

    static {
        ALLOWED.put(PENDING, EnumSet.of(LIVE, REJECTED, CANCELED));
        ALLOWED.put(LIVE, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELED));
        ALLOWED.put(PARTIALLY_FILLED, EnumSet.of(FILLED, CANCELED));
        ALLOWED.put(FILLED, EnumSet.noneOf(CryptoOrderState.class));
        ALLOWED.put(CANCELED, EnumSet.noneOf(CryptoOrderState.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(CryptoOrderState.class));
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * 同一状态允许（WS 会重复推 live/partially_filled 并带更新的累计成交量），
     * 但终态一律拒绝，包括同状态重推。
     */
    public boolean canTransitionTo(CryptoOrderState next) {
        if (next == null || isTerminal()) {
            return false;
        }
        return next == this || ALLOWED.get(this).contains(next);
    }

    public static CryptoOrderState of(String code) {
        for (CryptoOrderState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知订单状态：" + code);
    }
}
