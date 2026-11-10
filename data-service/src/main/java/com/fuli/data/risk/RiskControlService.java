package com.fuli.data.risk;

import com.fuli.data.crypto.CryptoAccountService;
import com.fuli.data.crypto.entity.CryptoAccount;
import com.fuli.data.crypto.mapper.CryptoAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 四道风控（阶段 3.4）：下单前拦截。
 *
 * <ol>
 *   <li><b>全局 kill switch</b>：{@code okx.risk.kill-switch}，紧急熔断一切下单；</li>
 *   <li><b>单笔金额上限</b>：{@code notional ≤ okx.risk.single-order-max-notional}；</li>
 *   <li><b>日累计上限</b>：当日累计 + 本次 {@code ≤ okx.risk.daily-max-notional}，跨天自动归零；</li>
 *   <li><b>最大回撤熔断</b>：账户权益相对峰值回撤 {@code ≥ okx.risk.max-drawdown} 即拒绝。</li>
 * </ol>
 *
 * <p>回撤基准（峰值权益）维护在 {@code risk_state.peak_equity}，首次下单取当前权益初始化，
 * 之后随权益创新高自动抬升；当前权益读 {@code crypto_account} 最新 {@code TOTAL} 快照。
 * 无余额快照时跳过回撤检查（fail-open）：风控不应在数据缺失时阻断首次交易。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskControlService {

    private final RiskStateMapper riskStateMapper;
    private final CryptoAccountMapper accountMapper;
    private final RiskControlProperties props;

    /**
     * 下单前四道风控检查，任一触发抛 {@link RiskControlException}。
     *
     * @param userId   下单人
     * @param notional 本单金额（size × 参考价，限价用 px、市价用最新价）
     */
    public void checkBeforeOrder(long userId, BigDecimal notional) {
        if (!props.isEnabled()) {
            return;
        }
        checkKillSwitch();
        checkSingleOrder(notional);
        checkDaily(userId, notional);
        checkDrawdown(userId);
    }

    /** 下单成功后累计当日金额（不更新峰值，峰值在 checkDrawdown 里随权益创新高维护）。 */
    @Transactional
    public void record(long userId, BigDecimal notional) {
        if (!props.isEnabled()) {
            return;
        }
        LocalDate today = LocalDate.now();
        RiskState state = stateOf(userId);
        BigDecimal daily = (state.getDailyDate() != null && state.getDailyDate().equals(today))
                ? state.getDailyNotional() : BigDecimal.ZERO;
        state.setDailyDate(today);
        state.setDailyNotional(daily.add(notional));
        save(state);
    }

    private void checkKillSwitch() {
        if (props.isKillSwitch()) {
            throw new RiskControlException("全局风控熔断已开启（kill switch），已拒绝下单");
        }
    }

    private void checkSingleOrder(BigDecimal notional) {
        BigDecimal max = props.getSingleOrderMaxNotional();
        if (max != null && max.signum() > 0 && notional.compareTo(max) > 0) {
            throw new RiskControlException("单笔金额 " + strip(notional)
                    + " 超过上限 " + strip(max) + "（okx.risk.single-order-max-notional）");
        }
    }

    private void checkDaily(long userId, BigDecimal notional) {
        BigDecimal max = props.getDailyMaxNotional();
        if (max == null || max.signum() <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        RiskState state = riskStateMapper.selectById(userId);
        BigDecimal used = BigDecimal.ZERO;
        if (state != null && state.getDailyDate() != null && state.getDailyDate().equals(today)
                && state.getDailyNotional() != null) {
            used = state.getDailyNotional();
        }
        if (used.add(notional).compareTo(max) > 0) {
            throw new RiskControlException("日累计金额 " + strip(used.add(notional))
                    + " 超过上限 " + strip(max) + "（okx.risk.daily-max-notional）");
        }
    }

    private void checkDrawdown(long userId) {
        BigDecimal threshold = props.getMaxDrawdown();
        if (threshold == null || threshold.signum() <= 0) {
            return;
        }
        BigDecimal eq = currentEquity(userId);
        if (eq == null || eq.signum() <= 0) {
            return; // 无余额快照，fail-open
        }
        BigDecimal peak = refreshPeak(userId, eq);
        BigDecimal drawdown = peak.subtract(eq).divide(peak, 8, RoundingMode.HALF_UP);
        if (drawdown.compareTo(threshold) > 0) {
            throw new RiskControlException("账户回撤 " + pct(drawdown)
                    + " 超过阈值 " + pct(threshold) + "，已熔断");
        }
    }

    /** 当前权益（crypto_account 最新 TOTAL 行）。无快照返回 null。 */
    private BigDecimal currentEquity(long userId) {
        List<CryptoAccount> latest = accountMapper.selectLatestPerCcy(userId);
        for (CryptoAccount acc : latest) {
            if (CryptoAccountService.TOTAL_CCY.equals(acc.getCcy()) && acc.getEquity() != null) {
                return acc.getEquity();
            }
        }
        return null;
    }

    /** 读峰值；若未初始化或当前权益创新高，则抬升并落库。返回最新峰值。 */
    @Transactional
    public BigDecimal refreshPeak(long userId, BigDecimal eq) {
        RiskState state = stateOf(userId);
        BigDecimal peak = state.getPeakEquity();
        if (peak == null || eq.compareTo(peak) > 0) {
            state.setPeakEquity(eq);
            save(state);
            return eq;
        }
        return peak;
    }

    private RiskState stateOf(long userId) {
        RiskState state = riskStateMapper.selectById(userId);
        if (state == null) {
            state = new RiskState();
            state.setUserId(userId);
            state.setDailyNotional(BigDecimal.ZERO);
            state.setDailyDate(LocalDate.now());
        }
        return state;
    }

    private void save(RiskState state) {
        if (riskStateMapper.selectById(state.getUserId()) == null) {
            riskStateMapper.insert(state);
        } else {
            riskStateMapper.updateById(state);
        }
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
