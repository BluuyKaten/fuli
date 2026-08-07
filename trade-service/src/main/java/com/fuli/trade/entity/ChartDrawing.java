package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 图表画线持久化
 */
@Data
@TableName("chart_drawing")
public class ChartDrawing implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String stockCode;
    private String period;

    /** JSON 格式的画线数据 */
    private String drawingData;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
