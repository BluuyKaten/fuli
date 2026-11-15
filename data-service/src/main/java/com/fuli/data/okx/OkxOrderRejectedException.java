package com.fuli.data.okx;

/** OKX 返回嵌套订单拒单结果时抛出的业务异常。 */
public final class OkxOrderRejectedException extends IllegalArgumentException {

    private final String sCode;

    private final String sMsg;

    public OkxOrderRejectedException(String sCode, String sMsg) {
        super("OKX 订单被拒绝 sCode=" + sCode + " sMsg=" + (sMsg == null ? "" : sMsg));
        this.sCode = sCode;
        this.sMsg = sMsg;
    }

    public String getSCode() {
        return sCode;
    }

    public String getSMsg() {
        return sMsg;
    }
}
