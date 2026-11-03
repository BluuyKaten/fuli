package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuli.trade.dto.PriceAlertDTO;
import com.fuli.trade.dto.PriceAlertVO;
import com.fuli.trade.entity.PriceAlert;
import com.fuli.trade.mapper.PriceAlertMapper;
import com.fuli.trade.service.PriceAlertService;
import com.fuli.trade.service.StockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PriceAlertServiceImpl extends ServiceImpl<PriceAlertMapper, PriceAlert> implements PriceAlertService {

    private final StockService stockService;

    public PriceAlertServiceImpl(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public Long createAlert(PriceAlertDTO dto, Long userId) {
        if (!StringUtils.hasText(dto.getStockCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "股票代码不能为空");
        }
        if (dto.getAlertType() == null || dto.getAlertType() < 1 || dto.getAlertType() > 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预警类型不合法");
        }
        if (dto.getTargetValue() == null || dto.getTargetValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标数值必须大于0");
        }

        PriceAlert alert = new PriceAlert();
        BeanUtils.copyProperties(dto, alert);
        alert.setUserId(userId);
        alert.setStatus(0); // 监控中
        if (!StringUtils.hasText(alert.getStockName())) {
            Map<String, Object> stockInfo = stockService.getStockInfo(dto.getStockCode());
            if (stockInfo != null && stockInfo.get("stockName") != null) {
                alert.setStockName(stockInfo.get("stockName").toString());
            } else {
                alert.setStockName(dto.getStockCode());
            }
        }

        save(alert);
        return alert.getId();
    }

    @Override
    public boolean updateAlert(Long id, PriceAlertDTO dto, Long userId) {
        PriceAlert existing = requireOwnedAlert(id, userId);

        if (dto.getAlertType() != null && dto.getAlertType() >= 1 && dto.getAlertType() <= 4) {
            existing.setAlertType(dto.getAlertType());
        }
        if (dto.getTargetValue() != null && dto.getTargetValue().compareTo(BigDecimal.ZERO) > 0) {
            existing.setTargetValue(dto.getTargetValue());
        }
        if (dto.getNotes() != null) {
            existing.setNotes(dto.getNotes());
        }
        // If updating target, reactivate to monitoring
        existing.setStatus(0);
        existing.setTriggeredTime(null);
        existing.setTriggeredValue(null);

        return updateById(existing);
    }

    @Override
    public boolean deleteAlert(Long id, Long userId) {
        requireOwnedAlert(id, userId);
        return removeById(id);
    }

    @Override
    public boolean toggleAlertStatus(Long id, Integer status, Long userId) {
        PriceAlert existing = requireOwnedAlert(id, userId);
        if (status == null || (status != 0 && status != 2)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只能设置为监控中(0)或已停用(2)");
        }
        existing.setStatus(status);
        if (status == 0) {
            existing.setTriggeredTime(null);
            existing.setTriggeredValue(null);
        }
        return updateById(existing);
    }

    @Override
    public List<PriceAlertVO> listAlerts(Long userId, Integer status) {
        LambdaQueryWrapper<PriceAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PriceAlert::getUserId, userId);
        if (status != null) {
            wrapper.eq(PriceAlert::getStatus, status);
        }
        wrapper.orderByDesc(PriceAlert::getId);

        List<PriceAlert> alerts = list(wrapper);
        if (alerts == null || alerts.isEmpty()) {
            return Collections.emptyList();
        }

        // 去重后批量查询行情，消除逐条 N+1
        Map<String, Map<String, Object>> quotes = fetchQuotes(alerts);
        return alerts.stream()
                .map(a -> enrichAndConvertToVO(a, quotes.get(a.getStockCode())))
                .collect(Collectors.toList());
    }

    @Override
    public List<PriceAlertVO> checkAndTriggerAlerts(Long userId) {
        List<PriceAlert> activeAlerts = list(new LambdaQueryWrapper<PriceAlert>()
                .eq(PriceAlert::getUserId, userId)
                .eq(PriceAlert::getStatus, 0));
        if (activeAlerts == null || activeAlerts.isEmpty()) {
            return Collections.emptyList();
        }
        return processAlerts(activeAlerts);
    }

    /**
     * 服务端轮询：定时检查所有监控中的预警并触发，用户无需停留在预警页面。
     * 每分钟一次，交易日才执行（简化判断，跳过周末）。
     */
    @Scheduled(fixedDelay = 60000)
    public void scheduledCheckAll() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }
        try {
            List<PriceAlert> activeAlerts = list(new LambdaQueryWrapper<PriceAlert>()
                    .eq(PriceAlert::getStatus, 0));
            if (activeAlerts == null || activeAlerts.isEmpty()) {
                return;
            }
            List<PriceAlertVO> triggered = processAlerts(activeAlerts);
            if (!triggered.isEmpty()) {
                log.info("预警轮询触发 {} 条", triggered.size());
            }
        } catch (Exception e) {
            log.warn("预警轮询异常: {}", e.getMessage());
        }
    }

    /** 批量去重查询行情 + 逐条判断触发，返回已触发的预警列表。 */
    private List<PriceAlertVO> processAlerts(List<PriceAlert> alerts) {
        Map<String, Map<String, Object>> quotes = fetchQuotes(alerts);
        List<PriceAlertVO> triggeredList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (PriceAlert alert : alerts) {
            try {
                Map<String, Object> latest = quotes.get(alert.getStockCode());
                if (latest == null || latest.isEmpty() || latest.get("closePrice") == null) {
                    continue;
                }

                BigDecimal currentPrice = toBigDecimal(latest.get("closePrice"));
                BigDecimal preClose = toBigDecimal(latest.get("preClose"));
                BigDecimal changePct = null;
                if (preClose != null && preClose.compareTo(BigDecimal.ZERO) > 0) {
                    changePct = currentPrice.subtract(preClose)
                            .divide(preClose, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }

                boolean triggered = false;
                BigDecimal triggeredVal = currentPrice;

                switch (alert.getAlertType()) {
                    case 1: // 止盈：当前价 >= 目标价
                        if (currentPrice.compareTo(alert.getTargetValue()) >= 0) {
                            triggered = true;
                        }
                        break;
                    case 2: // 止损：当前价 <= 目标价
                        if (currentPrice.compareTo(alert.getTargetValue()) <= 0) {
                            triggered = true;
                        }
                        break;
                    case 3: // 涨幅超过 %
                        if (changePct != null && changePct.compareTo(alert.getTargetValue()) >= 0) {
                            triggered = true;
                            triggeredVal = changePct;
                        }
                        break;
                    case 4: // 跌幅超过 % (targetValue 为正数，表示跌超该百分比)
                        BigDecimal lossThreshold = alert.getTargetValue().abs().negate();
                        if (changePct != null && changePct.compareTo(lossThreshold) <= 0) {
                            triggered = true;
                            triggeredVal = changePct;
                        }
                        break;
                    default:
                        break;
                }

                if (triggered) {
                    alert.setStatus(1); // 已触发
                    alert.setTriggeredTime(now);
                    alert.setTriggeredValue(triggeredVal);
                    updateById(alert);

                    PriceAlertVO vo = enrichAndConvertToVO(alert, latest);
                    vo.setCurrentPrice(currentPrice);
                    vo.setChangePct(changePct);
                    triggeredList.add(vo);
                }
            } catch (Exception e) {
                log.warn("检查预警异常: alertId={}, stockCode={}, error={}", alert.getId(), alert.getStockCode(), e.getMessage());
            }
        }

        return triggeredList;
    }

    /** 去重后批量查询行情（一次 Feign 批量调用）。 */
    private Map<String, Map<String, Object>> fetchQuotes(List<PriceAlert> alerts) {
        List<String> codes = alerts.stream()
                .map(PriceAlert::getStockCode)
                .distinct()
                .collect(Collectors.toList());
        if (codes.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Map<String, Object>> result = stockService.getLatestPrices(String.join(",", codes));
            return result != null ? result : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("批量查询行情失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private PriceAlert requireOwnedAlert(Long id, Long userId) {
        PriceAlert alert = getById(id);
        if (alert == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预警不存在");
        }
        if (!userId.equals(alert.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此预警");
        }
        return alert;
    }

    private PriceAlertVO enrichAndConvertToVO(PriceAlert alert, Map<String, Object> latest) {
        PriceAlertVO vo = new PriceAlertVO();
        BeanUtils.copyProperties(alert, vo);

        try {
            if (latest != null && latest.get("closePrice") != null) {
                BigDecimal currentPrice = toBigDecimal(latest.get("closePrice"));
                BigDecimal preClose = toBigDecimal(latest.get("preClose"));
                vo.setCurrentPrice(currentPrice);

                if (preClose != null && preClose.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal changePct = currentPrice.subtract(preClose)
                            .divide(preClose, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    vo.setChangePct(changePct.setScale(2, RoundingMode.HALF_UP));
                }

                if (alert.getTargetValue() != null && alert.getTargetValue().compareTo(BigDecimal.ZERO) > 0) {
                    if (alert.getAlertType() == 1 || alert.getAlertType() == 2) {
                        // 距离目标价距离百分比
                        BigDecimal diff = alert.getTargetValue().subtract(currentPrice);
                        BigDecimal distPct = diff.divide(currentPrice, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                        vo.setDistancePct(distPct.setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }
        } catch (Exception e) {
            // Ignore quote enrichment failures
        }

        return vo;
    }

    private BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return null;
        if (obj instanceof BigDecimal) return (BigDecimal) obj;
        if (obj instanceof Number) return BigDecimal.valueOf(((Number) obj).doubleValue());
        try {
            return new BigDecimal(obj.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
