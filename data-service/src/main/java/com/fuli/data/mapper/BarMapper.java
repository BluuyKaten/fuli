package com.fuli.data.mapper;

import com.fuli.data.entity.Bar;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 行情 K 线 Mapper。
 *
 * <p>主键是 {@code (instrument_code, period, ts)} 复合键，用不了 MyBatis-Plus 的单主键模型，
 * 因此不继承 {@code BaseMapper}，SQL 全部显式声明。
 *
 * <p><b>入参口径</b>：调用方传进来的 {@code volume}/{@code amount} 必须已经是「股/元」，
 * 换算只发生在同步出口 {@code TushareSyncServiceImpl.parseDailyData} 与历史迁移 SQL，
 * 这里不做任何单位处理——多一处换算就多一次静默出错的机会。
 */
@Mapper
public interface BarMapper {

    String COLUMNS = "instrument_code, period, ts, open_price, high_price, low_price, "
            + "close_price, pre_close, volume, amount";

    /**
     * 取最近 {@code limit} 根，降序返回（调用方反转）。
     *
     * <p>必须是降序取再反转：{@code ORDER BY ts ASC LIMIT n} 拿到的是「最早 n 根」，
     * 行数一样、同样走索引，只有看首末日期才能发现拿错了。
     */
    @Select("SELECT " + COLUMNS + " FROM bar "
            + "WHERE instrument_code = #{symbol} AND period = #{period} "
            + "ORDER BY ts DESC LIMIT #{limit}")
    List<Bar> selectRecentDesc(@Param("symbol") String symbol,
                               @Param("period") String period,
                               @Param("limit") int limit);

    /** 区间升序（{@code toTs} 为开区间上界）。 */
    @Select("<script>SELECT " + COLUMNS + " FROM bar "
            + "WHERE instrument_code = #{symbol} AND period = #{period} "
            + "<if test='fromTs != null'>AND ts &gt;= #{fromTs} </if>"
            + "<if test='toTs != null'>AND ts &lt; #{toTs} </if>"
            + "ORDER BY ts ASC</script>")
    List<Bar> selectRangeAsc(@Param("symbol") String symbol,
                             @Param("period") String period,
                             @Param("fromTs") Long fromTs,
                             @Param("toTs") Long toTs);

    /** 区间内最近 {@code limit} 根，降序返回（调用方反转）。 */
    @Select("<script>SELECT " + COLUMNS + " FROM bar "
            + "WHERE instrument_code = #{symbol} AND period = #{period} "
            + "<if test='fromTs != null'>AND ts &gt;= #{fromTs} </if>"
            + "<if test='toTs != null'>AND ts &lt; #{toTs} </if>"
            + "ORDER BY ts DESC LIMIT #{limit}</script>")
    List<Bar> selectRangeDesc(@Param("symbol") String symbol,
                              @Param("period") String period,
                              @Param("fromTs") Long fromTs,
                              @Param("toTs") Long toTs,
                              @Param("limit") int limit);

    /**
     * 全历史收盘价序列，供缩略条做 LTTB 降采样。
     *
     * <p>只取两列不回表；剔除收盘价为空的行——缩略条上一个空洞会在图上表现为断崖。
     */
    @Select("SELECT ts AS d, close_price AS c FROM bar "
            + "WHERE instrument_code = #{symbol} AND period = #{period} AND close_price IS NOT NULL "
            + "ORDER BY ts ASC")
    List<Map<String, Object>> selectCloseSeries(@Param("symbol") String symbol,
                                                @Param("period") String period);

    @Select("SELECT MAX(ts) FROM bar WHERE instrument_code = #{symbol} AND period = #{period}")
    Long selectMaxTs(@Param("symbol") String symbol, @Param("period") String period);

    /**
     * 批量取各标的的最新一根，供自选列表消除 N+1。
     *
     * <p>先按标的取 MAX(ts) 再回连，走的都是主键前缀，不扫全表。
     */
    @Select("<script>SELECT b.instrument_code, b.period, b.ts, b.open_price, b.high_price, b.low_price, "
            + "b.close_price, b.pre_close, b.volume, b.amount FROM bar b "
            + "JOIN (SELECT instrument_code, MAX(ts) AS mts FROM bar "
            + "      WHERE period = #{period} AND instrument_code IN "
            + "      <foreach item='s' collection='symbols' open='(' separator=',' close=')'>#{s}</foreach> "
            + "      GROUP BY instrument_code) m "
            + "  ON m.instrument_code = b.instrument_code AND m.mts = b.ts "
            + "WHERE b.period = #{period}</script>")
    List<Bar> selectLatestBatch(@Param("symbols") java.util.Collection<String> symbols,
                                @Param("period") String period);

    @Select("SELECT COUNT(*) FROM bar WHERE instrument_code = #{symbol} AND period = #{period}")
    long countBySymbol(@Param("symbol") String symbol, @Param("period") String period);

    /**
     * 整体清空某标的某周期。
     *
     * <p>美股同步用「先删后插」而不是纯 upsert：不同数据源的复权口径不同（腾讯只有前复权、
     * 东财原始价 + 因子），只 upsert 会在切换源时留下两种口径混在一起的序列——
     * 同一天的收盘价一半是原始价、一半是复权价，图上看起来像断层，而且很难归因。
     */
    @Delete("DELETE FROM bar WHERE instrument_code = #{symbol} AND period = #{period}")
    int deleteBySymbol(@Param("symbol") String symbol, @Param("period") String period);

    /**
     * 幂等 upsert：主键冲突时以新值覆盖。入参已是股/元口径。
     */
    @Insert("INSERT INTO bar (" + COLUMNS + ") VALUES ("
            + "#{instrumentCode}, #{period}, #{ts}, #{openPrice}, #{highPrice}, #{lowPrice}, "
            + "#{closePrice}, #{preClose}, #{volume}, #{amount}) "
            + "ON DUPLICATE KEY UPDATE open_price = VALUES(open_price), high_price = VALUES(high_price), "
            + "low_price = VALUES(low_price), close_price = VALUES(close_price), pre_close = VALUES(pre_close), "
            + "volume = VALUES(volume), amount = VALUES(amount)")
    int upsert(Bar bar);
}
