package com.fuli.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户自选股
 */
@Data
@TableName("user_watchlist")
public class UserWatchlist implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 纯数字代码：300750 */
    private String stockCode;

    private String stockName;

    private Integer sortOrder;

    private LocalDateTime createTime;
}
