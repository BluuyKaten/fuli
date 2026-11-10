package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoInstrument;
import org.apache.ibatis.annotations.Mapper;

/** 加密标的元数据 Mapper（V10）。P6 接产品配置同步时再补批量 upsert。 */
@Mapper
public interface CryptoInstrumentMapper extends BaseMapper<CryptoInstrument> {
}
