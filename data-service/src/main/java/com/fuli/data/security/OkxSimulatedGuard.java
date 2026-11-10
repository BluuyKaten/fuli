package com.fuli.data.security;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 旧「实盘开关」的 fail-fast 拦截（ADR-0009 决策 4 / Q12）。
 *
 * <p>代码里已经不存在关闭模拟盘的分支，但如果有人沿用 ADR-0008 时期的旧配置
 * （{@code okx.simulated=false} 或环境变量 {@code OKX_SIMULATED=false}），静默忽略会让人
 * 误以为自己切换过环境、也会给排障带来错误预期。这里选择启动即失败，把配置漂移
 * 变成一次显式报错，而不是无声地"没生效"。
 *
 * <p>注意：这是兜底提示，不是安全边界本身。真正的边界是
 * {@link com.fuli.data.config.OkxConfig#isSimulated()} 为常量，
 * 以及 {@code OkxClient} 无条件携带 {@code x-simulated-trading: 1}。
 */
@Component
public class OkxSimulatedGuard {

    public OkxSimulatedGuard(Environment environment) {
        String legacy = environment.getProperty("okx.simulated");
        if (legacy != null && "false".equalsIgnoreCase(legacy.trim())) {
            throw new IllegalStateException(
                    "OKX 实盘已被 ADR-0009 决策 4 永久禁用：检测到 okx.simulated=false"
                            + "（或 OKX_SIMULATED=false）。本系统只持有模拟盘凭证，所有私有请求恒定携带"
                            + " x-simulated-trading: 1，代码里没有实盘分支；需要实盘成交请在 OKX 侧由人工执行。"
                            + " 请移除该配置后重启。");
        }
    }
}
