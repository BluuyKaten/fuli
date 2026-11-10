package com.fuli.data.crypto.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.crypto.entity.CryptoPosition;
import org.apache.ibatis.annotations.Mapper;

/** OKX 持仓快照 Mapper（V10）。主键 pos_id 由 OKX 派生，用 updateById 即可。 */
@Mapper
public interface CryptoPositionMapper extends BaseMapper<CryptoPosition> {
}
