package com.fuli.data.repository;

import com.fuli.data.entity.Bar;
import com.fuli.data.mapper.BarMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 行情查询的唯一入口。SQL 不散落在 controller 里。
 *
 * <p>阶段 0 起数据源从 {@code stock_daily_data} 换成 {@code bar}（ADR-0007）：
 * 按 {@code (instrument_code, period, ts)} 聚簇，一张表覆盖全市场全周期。
 */
@Repository
@RequiredArgsConstructor
public class KlineQueryRepository {

    /** 日线周期代号。 */
    public static final String PERIOD_DAILY = "1d";

    private final BarMapper barMapper;

    /**
     * 取最近 n 根，按 ts 升序返回。
     *
     * <p><b>必须降序取再反转。</b>{@code ORDER BY ts ASC LIMIT n} 取到的是「最早 n 根」——
     * 上市初期的数据，是完全不同的一段。两者行数相同、同样走索引、耗时也一样，
     * 只有看首末日期才能发现拿错了。
     */
    public List<Bar> selectRecent(String symbol, String period, int n) {
        List<Bar> desc = barMapper.selectRecentDesc(symbol, period, n);
        Collections.reverse(desc);
        return desc;
    }

    /**
     * 按时间区间查询。{@code limit} 为空时返回区间全量（练习模块要对整段做连续性校验，
     * 截断会让结论失真）；非空时返回区间内最近 limit 根。
     *
     * @param fromTs 闭区间下界（含）
     * @param toTs   开区间上界（不含），由调用方按 Asia/Shanghai 自然日换算
     */
    public List<Bar> selectRange(String symbol, String period, Long fromTs, Long toTs, Integer limit) {
        if (limit != null && limit > 0) {
            List<Bar> desc = barMapper.selectRangeDesc(symbol, period, fromTs, toTs, limit);
            Collections.reverse(desc);
            return desc;
        }
        return barMapper.selectRangeAsc(symbol, period, fromTs, toTs);
    }

    /** 全历史收盘价序列 {@code [{d: ts, c: close}]}，供缩略条做 LTTB。 */
    public List<Map<String, Object>> selectCloseSeries(String symbol, String period) {
        return barMapper.selectCloseSeries(symbol, period);
    }

    public long count(String symbol, String period) {
        return barMapper.countBySymbol(symbol, period);
    }
}
