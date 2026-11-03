package com.fuli.trade.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuli.trade.dto.PriceAlertDTO;
import com.fuli.trade.dto.PriceAlertVO;
import com.fuli.trade.entity.PriceAlert;

import java.util.List;

public interface PriceAlertService extends IService<PriceAlert> {

    Long createAlert(PriceAlertDTO dto, Long userId);

    boolean updateAlert(Long id, PriceAlertDTO dto, Long userId);

    boolean deleteAlert(Long id, Long userId);

    boolean toggleAlertStatus(Long id, Integer status, Long userId);

    List<PriceAlertVO> listAlerts(Long userId, Integer status);

    List<PriceAlertVO> checkAndTriggerAlerts(Long userId);
}
