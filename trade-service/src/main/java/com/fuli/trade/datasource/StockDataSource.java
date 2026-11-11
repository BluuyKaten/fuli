package com.fuli.trade.datasource;

import com.fuli.trade.dto.KlineBarDTO;

import java.util.List;

/**
 * 股票数据源接口（支持多数据源切换）。
 *
 * <p>入参一律是<b>完整符号</b>（{@code 600519.SH} / {@code AAPL.US}），由数据源内部拆出市场——
 * 早先版本在 controller 里先 {@code pureCode} 丢掉后缀，数据源只能靠代码字面猜市场，
 * 结果美股（{@code AAPL} 不是 6 位数字）直接被判非法、K 线恒为空。市场判断只走
 * {@link com.fuli.common.api.util.StockCodeUtil}，不要在数据源里另写一套。
 */
public interface StockDataSource {

    /**
     * 获取分钟 K 线。
     *
     * @param symbol 完整符号，如 {@code 300750.SZ}、{@code AAPL.US}
     * @param period 分钟周期：1/5/15/30/60
     */
    List<KlineBarDTO> getMinuteData(String symbol, int period);

    /**
     * 获取周 K 线。
     *
     * @param symbol 完整符号
     */
    List<KlineBarDTO> getWeeklyData(String symbol);

    /**
     * 获取月 K 线。
     *
     * @param symbol 完整符号
     */
    List<KlineBarDTO> getMonthlyData(String symbol);

    /**
     * 数据源名称
     */
    String getName();

    /**
     * 是否可用
     */
    boolean isAvailable();
}
