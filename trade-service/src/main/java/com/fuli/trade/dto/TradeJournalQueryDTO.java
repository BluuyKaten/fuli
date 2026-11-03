package com.fuli.trade.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TradeJournalQueryDTO {
    private String stockCode;
    private LocalDate startDate;
    private LocalDate endDate;
    private String keyword;
    private String tag;
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
