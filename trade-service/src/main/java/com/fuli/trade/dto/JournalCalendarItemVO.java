package com.fuli.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JournalCalendarItemVO {
    private String date;
    private int count;
    private double avgEmotion;
    private List<String> titles;
}
