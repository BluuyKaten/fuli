package com.fuli.trade.quant;

/** 用户的本机采集配置，程序路径为空时识别正在运行的大智慧。 */
public record QuantAutomationConfig(boolean enabled, String executablePath,
                                    String scheduleTime, boolean startupCatchUp) {}
