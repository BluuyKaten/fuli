package com.fuli.data.okx;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 凭证加密配置（ADR-0010 决策 1）。
 *
 * <p>KEK（Key Encryption Key）只从环境变量 {@code OKX_CRED_KEK} 注入，
 * **不进数据库、不进代码、不进日志**。数据库里只有 AES-GCM 密文；
 * 拿到 data_db 的备份也解不开——这是「多账号凭证隔离」的核心边界。
 *
 * <p>缺失或长度不对时**启动即失败**：静默降级成「明文存」或「用默认密钥」都会把
 * 整个加密设计变成摆设，宁可起不来。
 *
 * <p>接受三种格式（推荐第一种）：
 * <ul>
 *   <li>Base64 编码的 32 字节：{@code openssl rand -base64 32}</li>
 *   <li>64 位十六进制：{@code openssl rand -hex 32}</li>
 *   <li>恰好 32 字节的原始 ASCII（方便本地调试，生产不要用）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "okx.credential")
public class OkxCredentialProperties {

    /** 主密钥材料，来自环境变量 {@code OKX_CRED_KEK}。 */
    private String kek = "";

    /** 当前写入使用的密钥版本，与 {@code okx_account.key_version} 对应。 */
    private int keyVersion = 1;

    @PostConstruct
    public void validate() {
        // 解析一次即可确认格式合法；真正的解析在 OkxCredentialCipher 里做。
        // 这里提前失败，比等到第一次写凭证时才 500 更早暴露部署问题。
        OkxKek.parse(kek);
    }
}
