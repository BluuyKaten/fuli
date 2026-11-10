package com.fuli.data.okx;

/** 凭证缺失/不可用。映射成 400，让调用方看到「去系统设置里配 Key」而不是一个笼统的 500。 */
public class OkxCredentialException extends RuntimeException {

    public OkxCredentialException(String message) {
        super(message);
    }

    public OkxCredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
