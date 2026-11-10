package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoFundingRate;
import org.apache.ibatis.annotations.Mapper;

/** 永续资金费率 Mapper（V10）。复合主键 (inst_id, ts_ms)，不要用按 ID 的方法。 */
@Mapper
public interface CryptoFundingRateMapper extends BaseMapper<CryptoFundingRate> {
}
