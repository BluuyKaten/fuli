package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoMarkPrice;
import org.apache.ibatis.annotations.Mapper;

/** 标记价格 / 指数价格 Mapper（V10）。复合主键 (inst_id, ts_ms)，不要用按 ID 的方法。 */
@Mapper
public interface CryptoMarkPriceMapper extends BaseMapper<CryptoMarkPrice> {
}
