package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * OKX 订单 Mapper（V10）。
 *
 * <p>通用 CRUD 走 {@link BaseMapper}；这里只显式声明状态机与对账需要的查询，
 * 因为它们的排序/过滤条件不能靠调用方自觉传对。
 */
@Mapper
public interface CryptoOrderMapper extends BaseMapper<CryptoOrder> {

    /** 幂等键最近一条；clOrdId 不设 UNIQUE（OKX 只保证挂单维度唯一），故取最新。 */
    @Select("SELECT * FROM crypto_order WHERE cl_ord_id = #{clOrdId} ORDER BY id DESC LIMIT 1")
    CryptoOrder selectLatestByClOrdId(@Param("clOrdId") String clOrdId);

    @Select("SELECT * FROM crypto_order WHERE ord_id = #{ordId} LIMIT 1")
    CryptoOrder selectByOrdId(@Param("ordId") String ordId);

    /** 断网重启后的孤儿单：已落库但还没发到交易所。 */
    @Select("SELECT * FROM crypto_order WHERE state = 'pending' ORDER BY id ASC")
    List<CryptoOrder> selectPending();

    /** 未完成订单，供 orders-pending 与 WS 快照对账。 */
    @Select("SELECT * FROM crypto_order WHERE state IN ('live','partially_filled') "
            + "ORDER BY u_time DESC LIMIT #{limit}")
    List<CryptoOrder> selectOpenOrders(@Param("limit") int limit);
}
