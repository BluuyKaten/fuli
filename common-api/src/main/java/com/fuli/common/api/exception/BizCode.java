package com.fuli.common.api.exception;

/**
 * 业务错误码常量(与前端 BizCode 对齐)
 */
public final class BizCode {

    private BizCode() {}

    /** 参数错误 */
    public static final int PARAM_ERROR = 400;

    /** 持仓不足 */
    public static final int POSITION_INSUFFICIENT = 4001;

    /** 资金不足 */
    public static final int CASH_INSUFFICIENT = 4002;

    /** 价格超涨跌停 */
    public static final int PRICE_OUT_OF_LIMIT = 4003;

    /** 缺少行情数据 */
    public static final int MISSING_MARKET_DATA = 4004;

    /** 仅允许删除最后一笔交易 */
    public static final int NOT_LAST_TRADE = 4005;

    /** 资金校验失败(防透支) */
    public static final int CASH_VALIDATION_FAILED = 4006;
}
