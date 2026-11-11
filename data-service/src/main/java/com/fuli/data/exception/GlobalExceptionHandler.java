package com.fuli.data.exception;

import com.fuli.common.api.Result;
import com.fuli.data.okx.OkxCredentialException;
import com.fuli.data.risk.RiskControlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * data-service 的业务异常出口。
 *
 * <p>为什么需要这个类：这里原本<b>没有</b> {@code @RestControllerAdvice}（auth/trade/analysis 都有），
 * 未捕获的异常会走 Spring 默认错误体 —— HTTP 500 + {@code {"status":500,"error":"Internal Server Error"}}。
 * 前端拿到 500 只会提示「服务器内部错误，请稍后重试」，于是：
 *
 * <ul>
 *   <li>「单笔金额 1000 超过上限」→ 用户以为系统坏了，实际是他自己该调小；</li>
 *   <li>「尚未配置 OKX 模拟盘凭证」→ 用户不知道该去录入 Key，只会反复重试。</li>
 * </ul>
 *
 * <p>这三类都是<b>可预期的业务结果</b>，不是系统故障，必须带着原因返回 400。
 *
 * <p>刻意<b>不</b>加 {@code @ExceptionHandler(Exception.class)} 兜底：那会把 404/405 这类
 * 框架语义也变成 400/500，反而更乱。真正的意外异常继续走 Spring 默认，日志里有堆栈。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 风控四道闸的拦截结果。用户需要知道是哪一道、差多少。 */
    @ExceptionHandler(RiskControlException.class)
    public ResponseEntity<Result<?>> handleRiskControl(RiskControlException e) {
        log.info("[RISK] 下单被风控拦截：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /** 没有可用凭证 / 密文解不开。提示里带「去哪里录入」，可操作。 */
    @ExceptionHandler(OkxCredentialException.class)
    public ResponseEntity<Result<?>> handleCredential(OkxCredentialException e) {
        log.info("[OKX] 凭证不可用：{}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.error(400, e.getMessage()));
    }

    /**
     * 参数/符号非法。
     *
     * <p>{@code StockCodeUtil.resolve}、{@code OkxSymbols.toInstId} 等校验方法都抛这个，
     * 属于调用方问题（400），不是服务端错误。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgument(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "请求参数不合法" : e.getMessage();
        return ResponseEntity.badRequest().body(Result.error(400, message));
    }
}
