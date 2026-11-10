package com.fuli.data.okx;

/**
 * 一套 OKX 凭证（已解密，仅存在于内存）。
 *
 * <p><b>这是全项目唯一持有明文的类型</b>，所以两件事必须守住：
 * <ol>
 *   <li>覆盖 {@link #toString()}——record 自动生成的 toString 会把 secretKey / passphrase
 *       原样拼进去，随便一句 {@code log.info("{}", creds)} 就把密钥写进了日志文件；</li>
 *   <li>不要放进 Map / 异常消息 / 审计 detail。{@code OkxAuditService} 只接受字符串，
 *       凭证永远不进那一层。</li>
 * </ol>
 *
 * <p>实例由 {@link OkxCredentialService} 按当前 JWT 的 userId 从 {@code okx_account} 解密得到，
 * 用完即弃，不做缓存（缓存会把「改 Key 后旧密钥还能用」变成一个隐蔽的窗口）。
 * 注意：HttpClient 连接池是 {@code OkxClient} 的单例字段，与凭证无关，因此不影响复用。
 */
public record OkxCredentials(String apiKey, String secretKey, String passphrase) {

    public OkxCredentials {
        if (isBlank(apiKey) || isBlank(secretKey) || isBlank(passphrase)) {
            // 不把具体哪个字段为空写进消息——异常消息会被 OkxController 原样回给调用方
            throw new IllegalArgumentException("OKX 凭证不完整：apiKey / secretKey / passphrase 三者都必填");
        }
    }

    /** 掩码后的 apiKey，给「查看已配置账户」的响应和日志用。 */
    public String maskedApiKey() {
        return mask(apiKey);
    }

    /**
     * 只保留头 4 位与尾 4 位。短于 12 位时全部打码——留头尾也无从推断，反而更安全。
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() < 12) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    /**
     * 刻意覆盖：secretKey 与 passphrase 完全不出现，apiKey 也只留掩码。
     * 任何日志、异常、调试输出走到这里都不会泄露。
     */
    @Override
    public String toString() {
        return "OkxCredentials[apiKey=" + maskedApiKey() + ", secretKey=***, passphrase=***]";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
