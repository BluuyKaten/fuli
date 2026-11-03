package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.common.api.security.AuthenticatedUser;
import com.fuli.trade.dto.PriceAlertDTO;
import com.fuli.trade.dto.PriceAlertVO;
import com.fuli.trade.service.PriceAlertService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/trade/alert")
public class PriceAlertController {

    private final PriceAlertService priceAlertService;
    private final HttpServletRequest request;

    public PriceAlertController(PriceAlertService priceAlertService, HttpServletRequest request) {
        this.priceAlertService = priceAlertService;
        this.request = request;
    }

    private Long getCurrentUserId() {
        return AuthenticatedUser.requireId(request);
    }

    @PostMapping
    public Result<Long> create(@RequestBody PriceAlertDTO dto) {
        Long id = priceAlertService.createAlert(dto, getCurrentUserId());
        return Result.success("创建预警成功", id);
    }

    @PutMapping("/{id}")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody PriceAlertDTO dto) {
        boolean ok = priceAlertService.updateAlert(id, dto, getCurrentUserId());
        return Result.success(ok ? "更新预警成功" : "更新预警失败", ok);
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        boolean ok = priceAlertService.deleteAlert(id, getCurrentUserId());
        return Result.success(ok ? "删除预警成功" : "删除预警失败", ok);
    }

    @PutMapping("/{id}/status")
    public Result<Boolean> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        boolean ok = priceAlertService.toggleAlertStatus(id, status, getCurrentUserId());
        return Result.success("设置成功", ok);
    }

    @GetMapping("/list")
    public Result<List<PriceAlertVO>> list(@RequestParam(required = false) Integer status) {
        List<PriceAlertVO> list = priceAlertService.listAlerts(getCurrentUserId(), status);
        return Result.success(list);
    }

    @PostMapping("/check")
    public Result<List<PriceAlertVO>> checkAlerts() {
        List<PriceAlertVO> triggered = priceAlertService.checkAndTriggerAlerts(getCurrentUserId());
        return Result.success(triggered);
    }
}
