package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 盘口<b>降采样</b>快照，对应 {@code data_db.crypto_book_snapshot}（V10）。
 *
 * <p>⚠️ 不是原始深度流。books / books-l2-tbt 的 10ms / 100ms 帧只在内存环形缓冲里，
 * 这里只落降采样后的稀疏帧（采样率由实施计划 P8 定），P2 只建表不写入。
 * 主键是 {@code (inst_id, ts_ms)} 复合键，不要用 selectById / updateById。
 * bids/asks 存 JSON 文本，MySQL JSON 列接受合法 JSON 字符串。
 */
@Data
@TableName("crypto_book_snapshot")
public class CryptoBookSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String instId;

    /** 毫秒 epoch。 */
    private Long tsMs;

    /** OKX 序号，用于校验连续性。 */
    private Long seqId;

    /** OKX checksum；-1 表示需要重新订阅全量。 */
    private String checksum;

    private String bids;

    private String asks;

    private LocalDateTime createTime;
}
