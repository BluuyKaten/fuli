package com.fuli.data.okx;

import com.fuli.data.okx.entity.OkxAccount;
import com.fuli.data.okx.mapper.OkxAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭证服务测试（ADR-0010 决策 1）：加密落库、掩码返回、按用户解析、失败可诊断。
 *
 * <p>注意 {@code BaseMapper.insert(T)} 与 {@code insert(Collection)} 是重载，
 * 裸 {@code any()} 会编译歧义，必须用 {@code any(OkxAccount.class)}。
 */
class OkxCredentialServiceTest {

    private static final long USER = 7L;

    private OkxAccountMapper mapper;

    private OkxCredentialService service;

    @BeforeEach
    void setUp() {
        mapper = mock(OkxAccountMapper.class);
        OkxCredentialProperties properties = new OkxCredentialProperties();
        properties.setKek("0123456789abcdef0123456789abcdef");
        service = new OkxCredentialService(mapper, new OkxCredentialCipher(properties));
    }

    @Test
    @DisplayName("新增：落库的是密文，不是明文")
    void saveEncryptsBeforePersisting() {
        when(mapper.selectByUserAndLabel(USER, "默认")).thenReturn(null);

        service.save(USER, new OkxAccountRequest("默认", "AKID1234567890", "SECRET-VALUE", "PASS-VALUE", true));

        ArgumentCaptor<OkxAccount> captor = ArgumentCaptor.forClass(OkxAccount.class);
        verify(mapper).insert(captor.capture());
        OkxAccount row = captor.getValue();
        assertEquals(USER, row.getUserId());
        assertEquals("AKID1234567890", row.getApiKey(), "apiKey 不是机密，明文存但只回掩码");
        assertNotNull(row.getSecretKeyEnc());
        assertNotNull(row.getPassphraseEnc());
        assertFalse(new String(row.getSecretKeyEnc(), StandardCharsets.ISO_8859_1).contains("SECRET-VALUE"));
        assertFalse(new String(row.getPassphraseEnc(), StandardCharsets.ISO_8859_1).contains("PASS-VALUE"));
        assertEquals(1, row.getKeyVersion());
        assertEquals(1, row.getEnabled());
    }

    @Test
    @DisplayName("更新：走 updateById，不新增行")
    void saveUpdatesExistingRow() {
        OkxAccount existing = new OkxAccount();
        existing.setId(3L);
        existing.setUserId(USER);
        existing.setLabel("默认");
        when(mapper.selectByUserAndLabel(USER, "默认")).thenReturn(existing);

        service.save(USER, new OkxAccountRequest("默认", "AKID-NEW", "S2", "P2", false));

        verify(mapper).updateById(any(OkxAccount.class));
        verify(mapper, never()).insert(any(OkxAccount.class));
        assertEquals("AKID-NEW", existing.getApiKey());
        assertEquals(0, existing.getEnabled());
    }

    @Test
    @DisplayName("列表：只回掩码，secretKey / passphrase 连字段都没有")
    void listReturnsMaskedView() {
        OkxAccount row = new OkxAccount();
        row.setUserId(USER);
        row.setLabel("模拟盘-主账户");
        row.setApiKey("AKID1234567890ABCD");
        row.setKeyVersion(1);
        row.setEnabled(1);
        when(mapper.selectByUser(USER)).thenReturn(List.of(row));

        List<OkxAccountView> views = service.list(USER);

        assertEquals(1, views.size());
        assertEquals("AKID****ABCD", views.get(0).apiKeyMasked());
        assertTrue(views.get(0).enabled());
    }

    @Test
    @DisplayName("解析：解密还原，且调用方拿到的就是当初写入的值")
    void resolveDecrypts() {
        OkxCredentialCipher cipher = cipher();
        OkxAccount row = new OkxAccount();
        row.setUserId(USER);
        row.setApiKey("AKID1234567890");
        row.setSecretKeyEnc(cipher.encrypt(USER, "secretKey", "SECRET-VALUE"));
        row.setPassphraseEnc(cipher.encrypt(USER, "passphrase", "PASS-VALUE"));
        row.setKeyVersion(1);
        row.setEnabled(1);
        when(mapper.selectEnabledByUser(USER)).thenReturn(row);

        OkxCredentials creds = service.resolve(USER);

        assertEquals("AKID1234567890", creds.apiKey());
        assertEquals("SECRET-VALUE", creds.secretKey());
        assertEquals("PASS-VALUE", creds.passphrase());
    }

    @Test
    @DisplayName("没有可用凭证：抛可操作的业务异常，而不是 NPE")
    void resolveWithoutCredentialFailsWithGuidance() {
        when(mapper.selectEnabledByUser(USER)).thenReturn(null);

        OkxCredentialException e = assertThrows(OkxCredentialException.class, () -> service.resolve(USER));
        assertTrue(e.getMessage().contains("系统设置"), "要告诉用户去哪里配");
        assertTrue(service.findEnabled(USER).isEmpty());
        assertFalse(service.hasEnabled(USER));
    }

    @Test
    @DisplayName("key_version 不支持：明确报错，不尝试用错密钥解")
    void unsupportedKeyVersionIsRejected() {
        OkxAccount row = new OkxAccount();
        row.setKeyVersion(9);
        when(mapper.selectEnabledByUser(USER)).thenReturn(row);

        OkxCredentialException e = assertThrows(OkxCredentialException.class, () -> service.resolve(USER));
        assertTrue(e.getMessage().contains("key_version"));
    }

    @Test
    @DisplayName("参数校验：缺字段 / label 过长 / label 含控制字符")
    void validatesInput() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(USER, new OkxAccountRequest("默认", "", "s", "p", true)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(USER, new OkxAccountRequest("默认", "k", "", "p", true)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(USER, new OkxAccountRequest("默认", "k", "s", "", true)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(USER, new OkxAccountRequest("a".repeat(65), "k", "s", "p", true)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(USER, new OkxAccountRequest("bad\nlabel", "k", "s", "p", true)));
        verify(mapper, never()).insert(any(OkxAccount.class));
    }

    private OkxCredentialCipher cipher() {
        OkxCredentialProperties properties = new OkxCredentialProperties();
        properties.setKek("0123456789abcdef0123456789abcdef");
        return new OkxCredentialCipher(properties);
    }
}
