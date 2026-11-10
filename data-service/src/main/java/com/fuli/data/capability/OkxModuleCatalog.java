package com.fuli.data.capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * P6：七模块能力的**受控端点清单**。
 *
 * <p>不为 60+ 个工具各写一个 Java 方法，而是把 OKX V5 端点集中登记成一张白名单表，
 * 由 {@link OkxCapabilityService} 统一执行。好处是：新增能力只加一行、
 * 路径不会被调用方任意拼接（防 SSRF/越权）、读写分级在一处可见。
 *
 * <p>{@code write=true} 的端点全部经过写前日志；{@code signed=true} 需要模拟盘凭证。
 */
public final class OkxModuleCatalog {

    public record Capability(String name, String module, String method,
                             String path, boolean write, boolean signed) {
    }

    private static Capability c(String name, String module, String method, String path,
                                boolean write, boolean signed) {
        return new Capability(name, module, method, path, write, signed);
    }

    public static final List<Capability> ALL = List.of(
            // ---- market（公共，免签）----
            c("market_ticker", "market", "GET", "/api/v5/market/ticker", false, false),
            c("market_candles", "market", "GET", "/api/v5/market/candles", false, false),
            c("market_history_candles", "market", "GET", "/api/v5/market/history-candles", false, false),
            c("market_books", "market", "GET", "/api/v5/market/books", false, false),
            c("market_trades", "market", "GET", "/api/v5/market/trades", false, false),
            c("market_funding_rate", "market", "GET", "/api/v5/public/funding-rate", false, false),
            c("market_open_interest", "market", "GET", "/api/v5/public/open-interest", false, false),
            c("market_mark_price", "market", "GET", "/api/v5/public/mark-price", false, false),
            c("market_index_ticker", "market", "GET", "/api/v5/market/index-tickers", false, false),
            c("market_instruments", "market", "GET", "/api/v5/public/instruments", false, false),
            // ---- spot ----
            c("spot_place_order", "spot", "POST", "/api/v5/trade/order", true, true),
            c("spot_cancel_order", "spot", "POST", "/api/v5/trade/cancel-order", true, true),
            c("spot_amend_order", "spot", "POST", "/api/v5/trade/amend-order", true, true),
            c("spot_batch_orders", "spot", "POST", "/api/v5/trade/batch-orders", true, true),
            c("spot_get_order", "spot", "GET", "/api/v5/trade/order", false, true),
            c("spot_get_open_orders", "spot", "GET", "/api/v5/trade/orders-pending", false, true),
            c("spot_get_fills", "spot", "GET", "/api/v5/trade/fills", false, true),
            // ---- swap / futures ----
            c("swap_place_order", "swap", "POST", "/api/v5/trade/order", true, true),
            c("swap_cancel_order", "swap", "POST", "/api/v5/trade/cancel-order", true, true),
            c("swap_get_positions", "swap", "GET", "/api/v5/account/positions", false, true),
            c("swap_get_leverage", "swap", "GET", "/api/v5/account/leverage-info", false, true),
            c("swap_set_leverage", "swap", "POST", "/api/v5/account/set-leverage", true, true),
            c("futures_place_order", "futures", "POST", "/api/v5/trade/order", true, true),
            c("futures_get_positions", "futures", "GET", "/api/v5/account/positions", false, true),
            // ---- option ----
            c("option_get_instruments", "option", "GET", "/api/v5/public/instruments", false, false),
            c("option_get_greeks", "option", "GET", "/api/v5/public/opt-summary", false, false),
            c("option_place_order", "option", "POST", "/api/v5/trade/order", true, true),
            // ---- account ----
            c("account_balance", "account", "GET", "/api/v5/account/balance", false, true),
            c("account_positions", "account", "GET", "/api/v5/account/positions", false, true),
            c("account_config", "account", "GET", "/api/v5/account/config", false, true),
            c("account_bills", "account", "GET", "/api/v5/account/bills", false, true),
            c("account_fee_rates", "account", "GET", "/api/v5/account/trade-fee", false, true),
            c("account_max_size", "account", "GET", "/api/v5/account/max-avail-size", false, true),
            // ---- bot（网格/DCA 策略）----
            c("grid_get_orders", "bot", "GET", "/api/v5/tradingBot/grid/orders-algo-pending", false, true),
            c("grid_create_order", "bot", "POST", "/api/v5/tradingBot/grid/order-algo", true, true),
            c("grid_stop_order", "bot", "POST", "/api/v5/tradingBot/grid/stop-order-algo", true, true),
            c("dca_get_orders", "bot", "GET", "/api/v5/tradingBot/recurring/orders-algo-pending", false, true)
    );

    public static final Set<String> MODULES = new LinkedHashSet<>(
            ALL.stream().map(Capability::module).toList());

    private OkxModuleCatalog() {
    }

    public static Capability require(String name) {
        return ALL.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未登记的 OKX 能力：" + name));
    }

    public static List<Capability> byModule(String module) {
        return ALL.stream().filter(c -> c.module().equals(module)).toList();
    }
}
