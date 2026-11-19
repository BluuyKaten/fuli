package com.fuli.data.okx;

/** OKX 已明确拒绝订单，结果确定，不应自动重发。 */
public class OkxOrderRejectedException extends IllegalArgumentException {

    private final String code;

    public OkxOrderRejectedException(String code, String message) {
        super("OKX 下单被拒绝" + (code == null ? "" : "（" + code + "）")
                + (message == null || message.isBlank() ? "" : "：" + message));
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
