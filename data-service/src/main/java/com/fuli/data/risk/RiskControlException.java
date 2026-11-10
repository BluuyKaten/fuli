package com.fuli.data.risk;

/**
 * 风控拒绝异常：下单被四道风控拦截时抛出，由调用方转成业务错误（400）。
 */
public class RiskControlException extends RuntimeException {

    public RiskControlException(String message) {
        super(message);
    }
}
