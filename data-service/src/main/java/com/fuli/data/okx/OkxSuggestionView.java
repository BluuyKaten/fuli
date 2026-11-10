package com.fuli.data.okx;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 建议单的对外视图。
 *
 * <p>给前端的字段刻意与订单对齐（instId/side/ordType/sz/px），
 * 但要靠 {@code status} 与 {@code source} 一眼看出「这是建议、不是成交」。
 */
public record OkxSuggestionView(Long id, String instId, String side, String ordType,
                                BigDecimal sz, BigDecimal px, String tdMode,
                                String reason, BigDecimal confidence, String status,
                                String source, LocalDateTime createTime) {
}
