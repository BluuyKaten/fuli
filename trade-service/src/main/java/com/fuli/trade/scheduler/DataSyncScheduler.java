package com.fuli.trade.scheduler;

import com.fuli.trade.entity.RealtimeQuote;
import com.fuli.trade.mapper.RealtimeQuoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 数据同步定时任务
 * 注意：实际项目中应调用真实数据源，这里先用模拟数据演示
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncScheduler {

    private final RealtimeQuoteMapper realtimeQuoteMapper;
    private final Random random = new Random();

    /**
     * 更新实时行情快照（交易时段每 3 秒）
     * 实际项目中：调用东方财富/ Tushare 获取真实数据
     */
    @Scheduled(fixedDelay = 3000)
    public void syncRealtimeQuote() {
        // 检查是否交易时段（9:30-15:00）
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();
        boolean isTradingHour = (hour == 9 && minute >= 30) || (hour >= 10 && hour < 15);
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        boolean isWeekday = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;

        if (!isTradingHour || !isWeekday) return;

        // 模拟几只热门股票的实时行情（实际项目中从数据源拉取）
        String[] hotStocks = {"300750.SZ", "600519.SH", "000001.SZ"};
        for (String code : hotStocks) {
            RealtimeQuote quote = realtimeQuoteMapper.selectById(code);
            boolean isNew = false;
            if (quote == null) {
                quote = new RealtimeQuote();
                quote.setStockCode(code);
                quote.setPreClose(val(150.0));
                quote.setOpenPrice(val(151.0));
                quote.setHighPrice(val(155.0));
                quote.setLowPrice(val(149.0));
                quote.setClosePrice(val(150.0));
                quote.setVol(100000L);
                quote.setAmount(val(15000000.0));
                // 初始化 5 档
                for (int i = 0; i < 5; i++) {
                    setBid(quote, i, 150.0 - (i + 1) * 0.1, 1000 + random.nextInt(5000));
                    setAsk(quote, i, 150.0 + (i + 1) * 0.1, 1000 + random.nextInt(5000));
                }
                isNew = true;
            } else {
                // 更新 5 档（模拟波动）
                for (int i = 0; i < 5; i++) {
                    double baseBid = 150.0 - (i + 1) * 0.1;
                    double baseAsk = 150.0 + (i + 1) * 0.1;
                    setBid(quote, i, baseBid + (random.nextDouble() - 0.5) * 0.2, 1000 + random.nextInt(5000));
                    setAsk(quote, i, baseAsk + (random.nextDouble() - 0.5) * 0.2, 1000 + random.nextInt(5000));
                }
            }
            // 模拟价格波动
            double change = (random.nextDouble() - 0.5) * 2;
            quote.setClosePrice(val(round(quote.getClosePrice().doubleValue() + change, 2)));
            quote.setUpdateTime(LocalDateTime.now());

            if (isNew) {
                realtimeQuoteMapper.insert(quote);
            } else {
                realtimeQuoteMapper.updateById(quote);
            }
        }
    }

    private void setBid(RealtimeQuote q, int level, double price, int vol) {
        switch (level) {
            case 0: q.setBid1Price(val(price)); q.setBid1Vol(vol); break;
            case 1: q.setBid2Price(val(price)); q.setBid2Vol(vol); break;
            case 2: q.setBid3Price(val(price)); q.setBid3Vol(vol); break;
            case 3: q.setBid4Price(val(price)); q.setBid4Vol(vol); break;
            case 4: q.setBid5Price(val(price)); q.setBid5Vol(vol); break;
        }
    }

    private void setAsk(RealtimeQuote q, int level, double price, int vol) {
        switch (level) {
            case 0: q.setAsk1Price(val(price)); q.setAsk1Vol(vol); break;
            case 1: q.setAsk2Price(val(price)); q.setAsk2Vol(vol); break;
            case 2: q.setAsk3Price(val(price)); q.setAsk3Vol(vol); break;
            case 3: q.setAsk4Price(val(price)); q.setAsk4Vol(vol); break;
            case 4: q.setAsk5Price(val(price)); q.setAsk5Vol(vol); break;
        }
    }

    private BigDecimal val(double d) {
        return new BigDecimal(String.format("%.2f", d));
    }

    private double round(double d, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(d * factor) / factor;
    }
}
