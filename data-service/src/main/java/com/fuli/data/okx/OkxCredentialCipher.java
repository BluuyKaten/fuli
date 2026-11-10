package com.fuli.data.okx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 凭证字段的 AES-256-GCM 加解密（ADR-0010 决策 1）。
 *
 * <p>存储格式：{@code iv(12) || ciphertext || tag(16)}，整体写进一个 VARBINARY 列。
 * <ul>
 *   <li><b>每行每字段独立随机 IV</b>：同一明文两次加密必须得到不同密文，
 *       否则「两个人用了同一把 Key」会从密文相等被直接看出来；</li>
 *   <li><b>GCM 自带完整性</b>：密文被改动一个字节就解密失败，不会静默还原出错误明文；</li>
 *   <li><b>AAD 绑定身份</b>：{@code okx-account:<userId>:<field>}。
 *       把 A 用户的密文行整行搬到 B 用户名下会解密失败——这是数据库被写坏/被误操作时
 *       的最后一道校验。AAD 不含 label，因为 label 属于可改的业务标签，不该成为密码学约束。</li>
 * </ul>
 *
 * <p>异常消息一律**不含密文、不含明文、不含 KEK**，只说「解密失败」。
 */
@Slf4j
@Component
public class OkxCredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int IV_BYTES = 12;

    private static final int TAG_BITS = 128;

    private static final String AAD_PREFIX = "okx-account:";

    private final SecretKeySpec key;

    private final SecureRandom random = new SecureRandom();

    /** 当前写入使用的密钥版本，读出来的行会与它比对。 */
    private final int keyVersion;

    public OkxCredentialCipher(OkxCredentialProperties properties) {
        this.key = new SecretKeySpec(OkxKek.parse(properties.getKek()), "AES");
        this.keyVersion = properties.getKeyVersion();
    }

    public int keyVersion() {
        return keyVersion;
    }

    /** 加密一个字段。每次调用都生成新的随机 IV，因此相同入参的两次结果必不相同。 */
    public byte[] encrypt(long userId, String field, String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密内容不能为空：" + field);
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId, field));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (Exception e) {
            // 不把 e 的 message 拼进去：某些 JCE 实现会在异常里带上 key 长度等细节
            throw new IllegalStateException("OKX 凭证加密失败（" + field + "）", e);
        }
    }

    /**
     * 解密一个字段。
     *
     * @throws IllegalStateException KEK 不匹配、密文被篡改、或数据格式损坏
     */
    public String decrypt(long userId, String field, byte[] blob) {
        if (blob == null || blob.length <= IV_BYTES) {
            throw new IllegalStateException("OKX 凭证密文为空或长度异常（" + field + "）");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(blob);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] sealed = new byte[buffer.remaining()];
            buffer.get(sealed);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(userId, field));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 刻意不区分「KEK 错」和「密文坏」——对外都是同一句，防信息泄露
            throw new IllegalStateException(
                    "OKX 凭证解密失败（" + field + "）：KEK 与密文不匹配或密文已被篡改。"
                            + "更换过 OKX_CRED_KEK 时需要重新录入凭证。", e);
        }
    }

    private static byte[] aad(long userId, String field) {
        return (AAD_PREFIX + userId + ":" + field).getBytes(StandardCharsets.UTF_8);
    }
}
