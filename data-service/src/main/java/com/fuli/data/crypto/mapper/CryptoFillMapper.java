package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoFill;
import org.apache.ibatis.annotations.Mapper;

/** OKX 成交明细 Mapper（V10）。复合主键 (inst_id, trade_id)，不要用按 ID 的方法。 */
@Mapper
public interface CryptoFillMapper extends BaseMapper<CryptoFill> {
}
