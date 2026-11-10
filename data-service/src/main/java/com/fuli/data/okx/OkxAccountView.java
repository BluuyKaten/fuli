package com.fuli.data.okx;

import java.time.LocalDateTime;

/**
 * 凭证的对外视图（GET /data/okx/account）。
 *
 * <p>**只含掩码**：{@code apiKey} 打码到只剩头尾各 4 位，secretKey / passphrase 连长度都不出现。
 * 前端「系统设置」页用这个结构渲染已配置账户，契约见 docs/okx-前端契约.md。
 */
public record OkxAccountView(String label, String apiKeyMasked, boolean enabled,
                             int keyVersion, LocalDateTime createTime, LocalDateTime updateTime) {
}
