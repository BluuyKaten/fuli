package com.fuli.data.okx;

import com.fuli.common.api.Result;
import com.fuli.data.crypto.entity.CryptoAccount;
import com.fuli.data.crypto.CryptoAccountService;
import com.fuli.data.okx.ws.OkxWsClient;
import com.fuli.data.security.OkxAuthz;
import com.fuli.data.security.OkxPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OKX 运行态自检：WS 连接状态 + 账户余额快照（ADR-0010 决策 5 / V12）。
 *
 * <p>把「WS 到底连上没有、最近有没有收到帧」暴露成接口，是 WS 真机联调的最低要求：
 * 只看日志很容易把「连上了但订阅被拒」当成正常。
 */
@RestController
@RequestMapping("/data/okx")
@RequiredArgsConstructor
public class OkxWsController {

    private final OkxWsClient wsClient;

    private final CryptoAccountService accountService;

    private final OkxAuthz authz;

    /** WS 状态：enabled / status / url / 订阅 / 丢弃帧 / 最近收帧时间。 */
    @GetMapping("/ws/status")
    public Result<Map<String, Object>> wsStatus(HttpServletRequest request) {
        authz.requireRead(request, "wsStatus");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", wsClient.isEnabled());
        m.put("status", wsClient.getStatus().name());
        m.put("url", wsClient.getUrl());
        // PUBLIC = 只订公共行情（无需登录）；PRIVATE = 私有频道（需要 okx.ws-owner-user-id）
        m.put("mode", wsClient.getUrl() != null && wsClient.getUrl().contains("/public") ? "PUBLIC" : "PRIVATE");
        m.put("subscriptions", wsClient.getSubscriptions());
        m.put("droppedFrames", wsClient.droppedFrames());
        long last = wsClient.getLastFrameAt();
        m.put("lastFrameAt", last == 0 ? null : Instant.ofEpochMilli(last).toString());
        m.put("lastFrameAgeSeconds", last == 0 ? null : (System.currentTimeMillis() - last) / 1000);
        m.put("lastFramePreview", wsClient.getLastFramePreview());
        return Result.success(m);
    }

    /** kill switch：立即断开并停止重连（进程重启后按配置恢复）。 */
    @PostMapping("/ws/stop")
    public Result<Map<String, Object>> wsStop(HttpServletRequest request) {
        OkxPrincipal principal = authz.requireWrite(request, "wsStop", "-");
        wsClient.stop();
        authz.auditResult("wsStop", principal, "SUCCESS", "-");
        return Result.success(Map.of("stopped", true, "status", wsClient.getStatus().name()));
    }

    /** 最新余额（每个币种一条 + TOTAL）。 */
    @GetMapping("/snapshots/latest")
    public Result<List<CryptoAccount>> latestSnapshots(HttpServletRequest request) {
        OkxPrincipal principal = authz.requireRead(request, "latestSnapshots");
        long userId = accountOwner(principal);
        return Result.success(accountService.latest(userId));
    }

    /** 某币种的历史快照，用于画权益曲线。 */
    @GetMapping("/snapshots")
    public Result<List<CryptoAccount>> snapshotHistory(HttpServletRequest request,
                                                       @RequestParam(value = "ccy", required = false) String ccy,
                                                       @RequestParam(value = "limit", required = false) Integer limit) {
        OkxPrincipal principal = authz.requireRead(request, "snapshotHistory");
        long userId = accountOwner(principal);
        String target = ccy == null || ccy.isBlank() ? CryptoAccountService.TOTAL_CCY : ccy.trim().toUpperCase();
        return Result.success(accountService.history(userId, target, limit == null ? 100 : limit));
    }

    /**
     * 服务账号没有自己的账户：这里只允许用户令牌（SERVICE 令牌读余额快照没有意义，
     * 而且会把「谁在看谁的余额」这件事变得含混）。
     */
    private long accountOwner(OkxPrincipal principal) {
        if (principal.isService()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "余额快照只对用户开放");
        }
        return principal.userId();
    }
}
