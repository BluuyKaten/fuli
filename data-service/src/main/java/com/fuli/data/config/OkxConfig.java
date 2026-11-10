package com.fuli.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OKX V5 接入配置。见 docs/adr/0008、ADR-0009、ADR-0010。
 *
 * <p><b>这里没有凭证字段</b>（ADR-0010 决策 1）。API Key / Secret / Passphrase 一律按
 * 当前 JWT 的 userId 从 {@code data_db.okx_account} 解密取得，
 * 因此不存在「全局凭证」，也就不存在「配错了就发到别人账户」这种故障模式。
 * 曾经存在的 {@code OKX_API_KEY} / {@code OKX_SECRET_KEY} / {@code OKX_PASSPHRASE}
 * 环境变量已删除——保留它们就等于留一条回退路径，而回退路径一定会被用上。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "okx")
public class OkxConfig {

    /** REST 基址。模拟盘与实盘同址，靠 header 区分，不要在这里填模拟盘域名。 */
    private String baseUrl = "https://www.okx.com";

    /**
     * 模拟盘是**系统常量**，不是可配置项（ADR-0009 决策 4 / Q12）。
     *
     * <p>原实现是一个可由 {@code OKX_SIMULATED=false} 关掉的布尔开关，而 OKX 的模拟盘与实盘
     * 共用 host、只差 {@code x-simulated-trading: 1} 一个头——留着这个开关就等于留着实盘写路径。
     * 现在没有 setter、没有配置绑定，{@code OkxClient} 对每个私有请求无条件携带该头，
     * 代码里不存在「实盘分支」。需要实盘成交时由人在 OKX 侧执行。
     */
    public boolean isSimulated() {
        return true;
    }

    private int timeoutMs = 10000;

    /**
     * 出网代理，如 {@code http://127.0.0.1:7890}。空表示直连。
     *
     * <p>国内网络常把 OKX 域名解析到黑洞地址（实测 {@code 169.254.0.2}），
     * 直连表现为「HTTP 000 / 立即失败」而不是超时，容易被误判成代码 bug。
     */
    private String proxy = "";

    /** 只接计价币为该值的现货对。 */
    private String quoteCcy = "USDT";

    /** 需要同步行情的 instId 列表，如 BTC-USDT。 */
    private List<String> symbols = List.of("BTC-USDT", "ETH-USDT", "SOL-USDT");

    /** 需要同步的周期（我们的 period 词汇），受 bar.chk_bar_period 约束。 */
    private List<String> bars = List.of("1d");

    /** 是否开启定时同步。默认关，避免本地起服务就往外发请求。 */
    private boolean autoSync = false;

    /** 全局总闸。默认 false：关着时所有写接口直接拒绝，不静默失败。 */
    private boolean tradeEnabled = false;

    /** 单笔下单数量上限（基础币），防手滑。0 表示不限制。 */
    private String maxOrderSize = "0";

    /**
     * WS 私有频道使用的账户身份（userId）。0 表示不登录私有频道，只跑公共行情。
     *
     * <p>WS 是一条后台长连接，没有「当前请求的 JWT」，所以必须显式指定一个账户身份，
     * 再从这个用户的 {@code okx_account} 行取凭证。这是**身份选择**，不是凭证回退：
     * 凭证仍然只来自数据库里的密文。
     */
    private long wsOwnerUserId = 0;

    /** MCP 私有工具（余额/持仓/挂单）使用的账户身份。0 表示这些工具返回「未配置身份」。 */
    private long mcpOwnerUserId = 0;

    /**
     * 服务账号（type=SERVICE，如 ai-service）读取私有数据时代表的账户身份。0 表示未配置。
     *
     * <p>服务账号没有自己的 OKX 账户，读余额/挂单必须有一个人来「代表」。
     * 这是身份选择，不是凭证回退：凭证依然只来自该用户的 {@code okx_account} 密文。
     * 未配置时服务令牌读私有端点会得到一条明确的 400，而不是 500 或空数据。
     *
     * <p>写操作**永远**不走这条：{@code OkxAuthz.requireWrite} 对服务主体直接 403。
     */
    private long agentOwnerUserId = 0;
}
