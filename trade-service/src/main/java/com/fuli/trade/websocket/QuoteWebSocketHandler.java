package com.fuli.trade.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.trade.entity.RealtimeQuote;
import com.fuli.trade.mapper.RealtimeQuoteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时行情 WebSocket 推送
 * 路径：ws://host/ws/quotes
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuoteWebSocketHandler extends TextWebSocketHandler {

    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final RealtimeQuoteMapper realtimeQuoteMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("[WebSocket] 新连接，当前 {} 个会话", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("[WebSocket] 断开连接，当前 {} 个会话", sessions.size());
    }

    /**
     * 定时推送（交易时段每 3 秒）
     */
    @Scheduled(fixedDelay = 3000)
    public void broadcastQuotes() {
        if (sessions.isEmpty()) return;

        try {
            // 查询最新行情（只取有更新的）
            List<RealtimeQuote> quotes = realtimeQuoteMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RealtimeQuote>()
                            .ge("update_time", java.time.LocalDateTime.now().minusSeconds(10))
            );

            if (quotes.isEmpty()) return;

            // 组装推送消息
            for (RealtimeQuote quote : quotes) {
                String message = buildQuoteMessage(quote);
                TextMessage textMessage = new TextMessage(message);

                for (WebSocketSession session : sessions) {
                    if (session.isOpen()) {
                        try {
                            synchronized (session) {
                                session.sendMessage(textMessage);
                            }
                        } catch (IOException e) {
                            log.warn("[WebSocket] 发送失败: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[WebSocket] 推送异常: {}", e.getMessage());
        }
    }

    private String buildQuoteMessage(RealtimeQuote quote) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "type", "quote",
                "code", quote.getStockCode(),
                "price", quote.getClosePrice(),
                "preClose", quote.getPreClose(),
                "bid1", quote.getBid1Price(),
                "ask1", quote.getAsk1Price(),
                "timestamp", System.currentTimeMillis()
        ));
    }
}
