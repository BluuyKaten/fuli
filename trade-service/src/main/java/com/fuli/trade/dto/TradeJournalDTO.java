package com.fuli.trade.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TradeJournalDTO {
    private String title;
    private String stockCode;
    private String stockName;
    private LocalDate journalDate;
    private Integer emotionScore;
    private String marketSentiment;
    private String strategyType;
    private String content;
    private String reflection;
    private String tags;
}
