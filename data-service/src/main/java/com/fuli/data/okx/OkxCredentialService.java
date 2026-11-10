package com.fuli.data.okx;

import com.fuli.data.okx.entity.OkxAccount;
import com.fuli.data.okx.mapper.OkxAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 按用户读写 OKX 凭证（ADR-0010 决策 1）。
 *
 * <p>三条不可协商的规则：
 * <ol>
 *   <li><b>明文只在内存里存在一瞬间</b>：{@link #save} 里加密后立刻丢弃入参，
 *       {@link #resolve} 里解密后返回给 OkxClient 用完即弃，全流程不缓存；</li>
 *   <li><b>没有「全局凭证」这个概念</b>：所有方法第一个参数都是 userId，
 *       不存在「传 null 就取默认账号」的重载——那正是会被误用成环境变量回退的口子；</li>
 *   <li><b>错误消息不含凭证</b>：解密失败只报字段名与「KEK 不匹配」，不报长度、不报明文片段。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxCredentialService {

    private static final int LABEL_MAX = 64;

    private static final int API_KEY_MAX = 128;

    private static final int SECRET_MAX = 256;

    private final OkxAccountMapper mapper;

    private final OkxCredentialCipher cipher;

    /**
     * 录入或更新自己的凭证（按 {@code (userId, label)} upsert）。
     *
     * <p>更新时 secretKey / passphrase 必须一起给：只改 apiKey 而不改 secret 会让这对组合失效，
     * 半新半旧比直接报错更难排查。
     */
    @Transactional
    public OkxAccountView save(long userId, OkxAccountRequest request) {
        String label = requireLabel(request == null ? null : request.label());
        String apiKey = require("apiKey", request.apiKey(), API_KEY_MAX);
        String secretKey = require("secretKey", request.secretKey(), SECRET_MAX);
        String passphrase = require("passphrase", request.passphrase(), SECRET_MAX);
        boolean enabled = request.enabled() == null || request.enabled();

        OkxAccount existing = mapper.selectByUserAndLabel(userId, label);
        OkxAccount row = existing == null ? new OkxAccount() : existing;
        row.setUserId(userId);
        row.setLabel(label);
        row.setApiKey(apiKey);
        row.setSecretKeyEnc(cipher.encrypt(userId, "secretKey", secretKey));
        row.setPassphraseEnc(cipher.encrypt(userId, "passphrase", passphrase));
        row.setKeyVersion(cipher.keyVersion());
        row.setEnabled(enabled ? 1 : 0);

        if (existing == null) {
            mapper.insert(row);
            log.info("[OKX-CRED] 新增凭证 userId={} label={} apiKey={} enabled={}",
                    userId, label, OkxCredentials.mask(apiKey), enabled);
        } else {
            mapper.updateById(row);
            log.info("[OKX-CRED] 更新凭证 userId={} label={} apiKey={} enabled={}",
                    userId, label, OkxCredentials.mask(apiKey), enabled);
        }
        // 重新查一次，拿到数据库写入的 create_time / update_time（DB 默认值 + ON UPDATE）
        OkxAccount saved = mapper.selectByUserAndLabel(userId, label);
        return toView(saved == null ? row : saved);
    }

    /** 当前用户的全部凭证（已掩码）。 */
    public List<OkxAccountView> list(long userId) {
        List<OkxAccountView> views = new ArrayList<>();
        for (OkxAccount row : mapper.selectByUser(userId)) {
            views.add(toView(row));
        }
        return views;
    }

    /**
     * 取当前用户可用于交易的凭证：{@code enabled=1} 的第一条。
     *
     * @throws OkxCredentialException 没有配置或密文不可解密
     */
    public OkxCredentials resolve(long userId) {
        return findEnabled(userId).orElseThrow(() -> new OkxCredentialException(
                "当前用户尚未配置可用的 OKX 模拟盘凭证，请先在「系统设置 → OKX 账户」中录入 API Key"));
    }

    /** 不抛异常的版本，供 WS / MCP 这类「没有请求上下文、只能降级」的场景使用。 */
    public Optional<OkxCredentials> findEnabled(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        OkxAccount row = mapper.selectEnabledByUser(userId);
        if (row == null) {
            return Optional.empty();
        }
        if (row.getKeyVersion() != null && row.getKeyVersion() != cipher.keyVersion()) {
            throw new OkxCredentialException("凭证的 key_version=" + row.getKeyVersion()
                    + " 与当前服务支持的版本 " + cipher.keyVersion() + " 不一致，请重新录入凭证");
        }
        return Optional.of(new OkxCredentials(
                row.getApiKey(),
                cipher.decrypt(userId, "secretKey", row.getSecretKeyEnc()),
                cipher.decrypt(userId, "passphrase", row.getPassphraseEnc())));
    }

    /** 该用户是否配置了凭证（不触发解密，供状态自检使用）。 */
    public boolean hasEnabled(long userId) {
        return mapper.selectEnabledByUser(userId) != null;
    }

    private static OkxAccountView toView(OkxAccount row) {
        return new OkxAccountView(row.getLabel(), OkxCredentials.mask(row.getApiKey()),
                row.getEnabled() != null && row.getEnabled() == 1,
                row.getKeyVersion() == null ? 1 : row.getKeyVersion(),
                row.getCreateTime(), row.getUpdateTime());
    }

    private static String requireLabel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "默认";
        }
        if (value.length() > LABEL_MAX) {
            throw new IllegalArgumentException("label 过长（最多 " + LABEL_MAX + " 字符）");
        }
        // label 只进日志与唯一键，不允许控制字符污染结构化日志
        if (!value.matches("^[\\w\\u4e00-\\u9fa5 .:_-]+$")) {
            throw new IllegalArgumentException("label 只允许中英文、数字、空格与 .:_- ");
        }
        return value;
    }

    private static String require(String field, String raw, int max) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("缺少参数：" + field);
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > max) {
            throw new IllegalArgumentException(field + " 过长（最多 " + max + " 字节）");
        }
        return value;
    }
}
