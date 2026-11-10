package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoAccount;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 账户余额快照 Mapper（V12）。 */
@Mapper
public interface CryptoAccountMapper extends BaseMapper<CryptoAccount> {

    /** 某用户每个币种的最新一条（含 TOTAL 行），按币种排序。 */
    @Select("SELECT a.id, a.user_id, a.ccy, a.eq AS equity, a.cash_bal, a.avail_bal, a.avail_eq, "
            + "a.frozen_bal, a.upl, a.iso_eq, a.dis_eq, a.u_time, a.snapshot_at "
            + "FROM crypto_account a "
            + "JOIN (SELECT ccy, MAX(u_time) AS mu FROM crypto_account WHERE user_id = #{userId} GROUP BY ccy) m "
            + "  ON a.ccy = m.ccy AND a.u_time = m.mu "
            + "WHERE a.user_id = #{userId} ORDER BY a.ccy")
    List<CryptoAccount> selectLatestPerCcy(@Param("userId") long userId);

    /** 某币种的历史快照，按时间倒序，用于画曲线。 */
    @Select("SELECT id, user_id, ccy, eq AS equity, cash_bal, avail_bal, avail_eq, "
            + "frozen_bal, upl, iso_eq, dis_eq, u_time, snapshot_at "
            + "FROM crypto_account WHERE user_id = #{userId} AND ccy = #{ccy} "
            + "ORDER BY u_time DESC LIMIT #{limit}")
    List<CryptoAccount> selectHistory(@Param("userId") long userId, @Param("ccy") String ccy,
                                      @Param("limit") int limit);

    /** 保留 90 天：分批删，避免一次锁太多行。 */
    @Delete("DELETE FROM crypto_account WHERE snapshot_at < #{cutoff} ORDER BY snapshot_at LIMIT #{limit}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
