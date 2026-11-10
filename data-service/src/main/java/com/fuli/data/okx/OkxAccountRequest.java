package com.fuli.data.okx;

/**
 * 录入凭证的请求体（POST /data/okx/account）。
 *
 * <p>这是一个**只进不出**的类型：它带着明文，所以不实现 {@code toString()} 之外的任何输出，
 * 且控制器在收到后立刻加密落库，不做回显、不写日志。
 * 不要把它直接返回给调用方，也不要塞进审计 detail。
 */
public record OkxAccountRequest(String label, String apiKey, String secretKey,
                                String passphrase, Boolean enabled) {

    /** 覆盖 record 默认实现，避免异常栈/调试器把明文带出去。 */
    @Override
    public String toString() {
        return "OkxAccountRequest[label=" + label + ", apiKey=" + OkxCredentials.mask(apiKey)
                + ", secretKey=***, passphrase=***, enabled=" + enabled + "]";
    }
}
