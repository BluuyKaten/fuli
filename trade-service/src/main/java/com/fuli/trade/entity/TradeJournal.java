package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("trade_journal")
public class TradeJournal implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String stockCode;

    private String stockName;

    private LocalDate journalDate;

    /** 1-恐慌焦虑 2-谨慎保守 3-理性平和 4-积极乐观 5-亢奋冲动 */
    private Integer emotionScore;

    /** 市场情绪：如 普涨、普跌、结构分化、震荡筑底、冰点情绪 */
    private String marketSentiment;

    /** 策略分类：如 趋势突破、超跌反弹、逢低定投、做T减仓、网格波段 */
    private String strategyType;

    /** 复盘逻辑与心得内容 */
    private String content;

    /** 得失反思与经验提炼 */
    private String reflection;

    /** 标签（逗号分隔） */
    private String tags;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
