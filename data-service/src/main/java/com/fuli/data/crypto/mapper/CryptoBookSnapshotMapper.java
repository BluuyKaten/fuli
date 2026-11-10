package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoBookSnapshot;
import org.apache.ibatis.annotations.Mapper;

/** 盘口降采样快照 Mapper（V10）。原始 10ms 流不落库，P8 才写入。 */
@Mapper
public interface CryptoBookSnapshotMapper extends BaseMapper<CryptoBookSnapshot> {
}
