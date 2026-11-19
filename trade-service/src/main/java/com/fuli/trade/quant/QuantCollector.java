package com.fuli.trade.quant;

/** 大智慧本机采集器的适配器接口。 */
@FunctionalInterface
public interface QuantCollector {
    QuantRankingDtos.CollectResult collect();

    default QuantRankingDtos.CollectResult collect(String executablePath) { return collect(); }
}
