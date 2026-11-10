package com.fuli.data.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 美股复权因子（阶段 1.4）。
 *
 * <p>东财 fqt=1 是前复权、fqt=0 是不复权。相对因子 {@code factor = fwd_close / raw_close}，
 * 最新日恒为 1，往前每过一个拆股日（1 拆 N）就再除以 N。落 {@code adj_factor} 后，
 * {@code MarketBarsService.applyForward} 的 {@code price × factor / factor_latest} 即可消除
 * 拆股造成的假暴跌（如 AAPL 2020-08-31 1 拆 4 的 -75% 假阴线）。
 *
 * <p>这是<b>派生数据</b>，随时可由 fqt=0/fqt=1 重算，不作为事实源；低价股因小数位少噪声偏大，
 * 属可接受误差。整数比校准用于消除价格精度噪声：检测相邻 factor 的整数比跳变并归一化。
 */
public final class UsAdjustFactor {

    private static final BigDecimal ONE_PCT = new BigDecimal("0.01");
    private static final BigDecimal TWO_PCT = new BigDecimal("0.02");

    private UsAdjustFactor() {
    }

    /**
     * 计算每个交易日的相对复权因子（整数比校准后）。
     *
     * @param raw 不复权日线（升序）
     * @param fwd 前复权日线（升序）
     * @return date → factor（> 0）
     */
    public static Map<LocalDate, BigDecimal> compute(List<UsMarket.UsCandle> raw,
                                                     List<UsMarket.UsCandle> fwd) {
        Map<LocalDate, BigDecimal> fwdClose = new HashMap<>(fwd.size() * 2);
        for (UsMarket.UsCandle c : fwd) {
            fwdClose.put(c.date(), c.close());
        }

        List<LocalDate> dates = new ArrayList<>();
        List<BigDecimal> factors = new ArrayList<>();
        for (UsMarket.UsCandle c : raw) {
            BigDecimal f = fwdClose.get(c.date());
            if (f == null || c.close() == null || c.close().signum() == 0) {
                continue;
            }
            dates.add(c.date());
            factors.add(f.divide(c.close(), 8, RoundingMode.HALF_UP));
        }

        calibrate(factors);

        Map<LocalDate, BigDecimal> result = new HashMap<>(dates.size() * 2);
        for (int i = 0; i < dates.size(); i++) {
            result.put(dates.get(i), factors.get(i));
        }
        return result;
    }

    /**
     * 从最新往旧消除噪声：同一段（ratio≈1）归一为基准；整数比跳变（1 拆 N）校准为精确整数比。
     */
    static void calibrate(List<BigDecimal> factors) {
        int n = factors.size();
        if (n <= 1) {
            return;
        }
        BigDecimal base = factors.get(n - 1);
        for (int i = n - 2; i >= 0; i--) {
            BigDecimal actual = factors.get(i);
            // 拆股时 actual < base，ratio = base/actual > 1；无拆股时 ratio ≈ 1
            BigDecimal ratio = base.divide(actual, 8, RoundingMode.HALF_UP);
            BigDecimal nearestInt = ratio.setScale(0, RoundingMode.HALF_UP);
            boolean nearOne = ratio.subtract(BigDecimal.ONE).abs().compareTo(ONE_PCT) <= 0;
            boolean nearIntGt1 = nearestInt.compareTo(BigDecimal.ONE) > 0
                    && ratio.subtract(nearestInt).abs().compareTo(TWO_PCT) <= 0;
            if (nearOne) {
                factors.set(i, base);
            } else if (nearIntGt1) {
                BigDecimal calibrated = base.divide(nearestInt, 8, RoundingMode.HALF_UP);
                factors.set(i, calibrated);
                base = calibrated;
            } else {
                factors.set(i, actual);
                base = actual;
            }
        }
    }
}
