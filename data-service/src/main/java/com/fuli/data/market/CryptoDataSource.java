package com.fuli.data.market;

import java.util.List;

/**
 * 加密行情数据源接口。多源路由：主源失败/空自动切备用（见 {@link CryptoSyncService}）。
 *
 * <p>入参 {@code symbol} 为完整内部符号（如 {@code BTC-USDT.CRYPTO}），各实现自行换算成
 * 交易所格式；{@code interval} 为 {@code 1d} / {@code 4h}；时间范围 {@code [fromTs, toTs)}
 * 为 Unix 秒（UTC）。
 */
public interface CryptoDataSource {

    /** 数据源名称（用于路由与日志）。 */
    String getName();

    /** 是否可用（配置关闭时返回 false）。 */
    boolean isAvailable();

    /**
     * 拉取 {@code [fromTs, toTs)} 区间的 K 线，按 ts 升序返回。
     *
     * @throws RuntimeException 网络或解析失败（由调用方兜底切换备用源）
     */
    List<CryptoCandle> fetch(String symbol, String interval, long fromTs, long toTs);
}
