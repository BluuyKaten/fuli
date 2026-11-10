package com.fuli.data.okx;

import com.fuli.data.config.OkxConfig;
import com.fuli.data.security.OkxPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把「调用主体」映射成「用谁的 OKX 账户」（ADR-0010 决策 4）。
 *
 * <p>用户令牌：就是他自己。
 * <br>服务令牌：服务账号没有 OKX 账户，只能代表 {@code okx.agent-owner-user-id} 指定的那个用户。
 *
 * <p><b>这是身份选择，不是凭证回退</b>。凭证依旧只从 {@code okx_account} 解密；
 * 配置里出现的只是一个 userId，不是任何一枚 Key。把两者混为一谈，
 * 就会退回到「服务端存一份全局凭证」的老路——那正是本次要消除的东西。
 *
 * <p>没配置时明确拒绝：让 Agent 读到「空数据」比读到 400 更危险，
 * 因为空数据会被当成「这个账户没有挂单」。
 */
@Component
@RequiredArgsConstructor
public class OkxAccountIdentity {

    private final OkxConfig config;

    /**
     * @return 私有请求应使用的账户 userId
     * @throws OkxCredentialException 服务账号未配置代理身份
     */
    public long requireAccountUserId(OkxPrincipal principal) {
        if (!principal.isService()) {
            return principal.userId();
        }
        long owner = config.getAgentOwnerUserId();
        if (owner <= 0) {
            throw new OkxCredentialException(
                    "服务账号读取账户数据需要代理身份：请设置 okx.agent-owner-user-id "
                            + "为某个已录入 OKX 凭证的用户 ID，或改用该用户的令牌");
        }
        return owner;
    }

    /** 只用于展示/自检，不抛异常。 */
    public Long accountUserIdOrNull(OkxPrincipal principal) {
        if (!principal.isService()) {
            return principal.userId();
        }
        long owner = config.getAgentOwnerUserId();
        return owner > 0 ? owner : null;
    }
}
