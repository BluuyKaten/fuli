package com.fuli.data.okx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证加密的验收测试（ADR-0010 决策 1）。
 *
 * <p>三条必须成立的性质：
 * <ol>
 *   <li>同一明文两次加密**密文不同**（每行随机 IV）——否则「两个人用了同一把 Key」会从密文相等被看出来；</li>
 *   <li>解密可还原；</li>
 *   <li>错误 KEK 必须解密失败，而不是悄悄返回乱码。</li>
 * </ol>
 */
class OkxCredentialCipherTest {

    private static final String KEK_ASCII = "0123456789abcdef0123456789abcdef"; // 32 字节

    private static final long USER = 7L;

    private static OkxCredentialCipher cipher(String kek) {
        OkxCredentialProperties properties = new OkxCredentialProperties();
        properties.setKek(kek);
        return new OkxCredentialCipher(properties);
    }

    @Test
    @DisplayName("同一明文两次加密：密文不同，但都能解回同一明文")
    void sameInputProducesDifferentCiphertext() {
        OkxCredentialCipher cipher = cipher(KEK_ASCII);
        byte[] first = cipher.encrypt(USER, "secretKey", "SECRET-VALUE");
        byte[] second = cipher.encrypt(USER, "secretKey", "SECRET-VALUE");

        assertNotEquals(Base64.getEncoder().encodeToString(first),
                Base64.getEncoder().encodeToString(second),
                "随机 IV 失效：两次加密结果相同会让密文可比较");
        assertEquals("SECRET-VALUE", cipher.decrypt(USER, "secretKey", first));
        assertEquals("SECRET-VALUE", cipher.decrypt(USER, "secretKey", second));
    }

    @Test
    @DisplayName("密文里不含明文（不是「看起来像加密」）")
    void ciphertextDoesNotContainPlaintext() {
        byte[] blob = cipher(KEK_ASCII).encrypt(USER, "passphrase", "P@ssw0rd-Plain");
        String asText = new String(blob, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("P@ssw0rd-Plain"));
        assertTrue(blob.length > 12 + 16, "至少要有 iv(12)+tag(16)+密文");
    }

    @Test
    @DisplayName("换一把 KEK：解密必须失败")
    void wrongKekFailsToDecrypt() {
        byte[] blob = cipher(KEK_ASCII).encrypt(USER, "secretKey", "SECRET-VALUE");
        OkxCredentialCipher other = cipher("ffffffffffffffffffffffffffffffff");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> other.decrypt(USER, "secretKey", blob));
        assertTrue(e.getMessage().contains("解密失败"));
        // 错误消息里不能出现密文或明文片段
        assertFalse(e.getMessage().contains("SECRET-VALUE"));
    }

    @Test
    @DisplayName("密文被改一个字节：GCM 完整性校验必须拒绝")
    void tamperedCiphertextIsRejected() {
        OkxCredentialCipher cipher = cipher(KEK_ASCII);
        byte[] blob = cipher.encrypt(USER, "secretKey", "SECRET-VALUE");
        blob[blob.length - 1] ^= 0x01;

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(USER, "secretKey", blob));
    }

    @Test
    @DisplayName("AAD 绑定用户：A 的密文搬到 B 名下解不开")
    void ciphertextIsBoundToItsOwner() {
        OkxCredentialCipher cipher = cipher(KEK_ASCII);
        byte[] blob = cipher.encrypt(1L, "secretKey", "SECRET-VALUE");

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(2L, "secretKey", blob));
    }

    @Test
    @DisplayName("KEK 支持 Base64(32B) / 64 位 Hex / 32 字节 ASCII 三种写法")
    void kekAcceptsThreeFormats() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        assertArrayEquals(raw, OkxKek.parse(Base64.getEncoder().encodeToString(raw)));

        StringBuilder hex = new StringBuilder();
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        assertArrayEquals(raw, OkxKek.parse(hex.toString()));
        assertArrayEquals(KEK_ASCII.getBytes(StandardCharsets.UTF_8), OkxKek.parse(KEK_ASCII));
    }

    @Test
    @DisplayName("KEK 缺失或长度不对：启动期直接失败，并说明怎么生成")
    void invalidKekFailsFastWithActionableMessage() {
        for (String bad : new String[]{"", "   ", "too-short", "0123456789abcdef"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> OkxKek.parse(bad));
            assertTrue(e.getMessage().contains("OKX_CRED_KEK"), "错误信息要指路到环境变量");
        }
        // 构造 cipher 时也会走同一层校验，保证「起不来」而不是「起来但存明文」
        OkxCredentialProperties properties = new OkxCredentialProperties();
        properties.setKek("too-short");
        assertThrows(IllegalStateException.class, () -> new OkxCredentialCipher(properties));
    }
}
