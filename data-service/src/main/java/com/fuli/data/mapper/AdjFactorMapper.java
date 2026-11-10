package com.fuli.data.mapper;

import com.fuli.data.entity.AdjFactor;
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
}
