package com.fuli.data.okx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * OKX 凭证行，对应 {@code data_db.okx_account}（V11）。
 *
 * <p><b>密文字段是 {@code byte[]}</b>：{@code iv(12) || ciphertext || tag(16)}。
 * 实体不提供任何「明文 getter」——解密只在 {@link com.fuli.data.okx.OkxCredentialService}
 * 内部发生，避免有人顺手把明文塞进 DTO 或日志。
 */
@Data
@TableName("okx_account")
public class OkxAccount implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（auth_db.sys_user.id）。凭证按用户物理隔离，跨用户读取没有任何入口。 */
    private Long userId;

    /** 用户自定义标签，唯一键 (user_id, label) 的一半。 */
    private String label;

    /** OKX API Key，非机密，但对外只回掩码。 */
    private String apiKey;

    /** AES-256-GCM 密文，见 {@link com.fuli.data.okx.OkxCredentialCipher}。 */
    private byte[] secretKeyEnc;

    private byte[] passphraseEnc;

    /** KEK 版本，将来轮换时用于新旧密钥并存。 */
    private Integer keyVersion;

    /** 1-可用于交易 0-停用（停用不删除密文）。 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
