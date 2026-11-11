package com.fuli.data.mapper;

import com.fuli.data.entity.AdjFactor;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 复权因子 Mapper。
 */
@Mapper
public interface AdjFactorMapper {

    @Insert("INSERT INTO adj_factor (instrument_code, ts, factor) "
            + "VALUES (#{instrumentCode}, #{ts}, #{factor}) "
            + "ON DUPLICATE KEY UPDATE factor = VALUES(factor)")
    int upsert(AdjFactor adjFactor);

    /** 区间因子，升序（{@code toTs} 开区间上界）。 */
    @Select("<script>SELECT instrument_code, ts, factor FROM adj_factor "
            + "WHERE instrument_code = #{symbol} "
            + "<if test='fromTs != null'>AND ts &gt;= #{fromTs} </if>"
            + "<if test='toTs != null'>AND ts &lt; #{toTs} </if>"
            + "ORDER BY ts ASC</script>")
    List<AdjFactor> selectRange(@Param("symbol") String symbol,
                                @Param("fromTs") Long fromTs,
                                @Param("toTs") Long toTs);

    /** 最新一根的因子（前复权的分母）。 */
    @Select("SELECT factor FROM adj_factor WHERE instrument_code = #{symbol} ORDER BY ts DESC LIMIT 1")
    BigDecimal selectLatestFactor(@Param("symbol") String symbol);

    @Select("SELECT COUNT(*) FROM adj_factor WHERE instrument_code = #{symbol}")
    long countBySymbol(@Param("symbol") String symbol);

    /**
     * 清空某标的的复权因子。
     *
     * <p>美股换源时必须清：腾讯只有前复权、不产出因子，若留着东财时代写下的因子，
     * {@code adjust=forward} 会把已经是前复权的价格再乘一遍，整体缩放成一条错误的曲线。
     */
    @Delete("DELETE FROM adj_factor WHERE instrument_code = #{symbol}")
    int deleteBySymbol(@Param("symbol") String symbol);
}
