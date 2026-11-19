package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.CryptoOrderState;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.risk.RiskControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OKX 量化交易（现货、现金账户）。见 docs/adr/0008 决策 7、ADR-0009 决策 4/5、ADR-0010 决策 1/2。
 *
 * <p>下单约束（四道闸，缺一不可）：
 * <ol>
 *   <li>{@code okx.trade-enabled=true} —— 全局总闸，默认 false，关着时**抛异常**而不是静默返回；</li>
 *   <li>当前用户 {@code sys_user.okx_trade_enabled=1} —— 由 controller 层的 {@code OkxAuthz} 校验，
 *       本服务假定调用方已经验过；</li>
 *   <li>当前用户的 {@code okx_account} 里有一条 {@code enabled=1} 的凭证 —— 否则这里抛
 *       {@link OkxCredentialException}，不会回退到任何全局凭证；</li>
 *   <li>模拟盘是系统常量：{@code OkxClient} 对每个私有请求无条件加
 *       {@code x-simulated-trading: 1}，代码里不存在关闭它的分支。</li>
 * </ol>
 *
 * <p><b>写前日志（P4）</b>：下单顺序固定为
 * {@code createPending → 发网络 → markSubmitted}，中间任何一步崩了，库里都留得下痕迹。
 * 断网重启时 {@code findOrphans()} 能捞出发到一半的单；状态后续由 WS {@code orders} 频道推进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxTradeService {

    private static final Set<String> SIDES = Set.of("buy", "sell");

    private static final Set<String> ORD_TYPES = Set.of("market", "limit");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final OkxConfig config;

    private final OkxClient client;

    private final CryptoOrderService orderLog;

    private final OkxCredentialService credentials;

    private final RiskControlService riskControl;

    /** 账户配置：{@code acctLv} 决定能下什么单（1 简单 / 2 单币种保证金 / 3 跨币种 / 4 组合）。 */
    public JsonNode accountConfig(long userId) {
        return client.privateGet(credentials.resolve(userId), "/api/v5/account/config", null);
    }

    /** 账户余额。不传 ccy 返回全部币种。 */
    public JsonNode balance(long userId, String ccy) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("ccy", ccy);
        return client.privateGet(credentials.resolve(userId), "/api/v5/account/balance", q);
    }

    /** 持仓。现货一般没有持仓，合约要看 instType=SWAP。 */
    public JsonNode positions(long userId, String instType) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("instType", instType == null || instType.isBlank() ? "SWAP" : instType);
        return client.privateGet(credentials.resolve(userId), "/api/v5/account/positions", q);
    }

    /** 未成交订单。 */
    public JsonNode pendingOrders(long userId, String instType) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("instType", instType == null || instType.isBlank() ? "SPOT" : instType);
        return client.privateGet(credentials.resolve(userId), "/api/v5/trade/orders-pending", q);
    }

    /**
     * 下单（SPOT / cash）。
     *
     * @param userId 下单人，凭证与写前日志都按他归属
     * @param sz     基础币数量（市价买单也可用 tgtCcy 指定按计价币，本期不开放）
     * @param px     限价必填；市价传 null
     * @param clOrdId 客户自定义订单 ID；为空时生成一个 32 位 UUID 幂等键
     * @throws IllegalStateException 交易开关未开、数量越界、参数非法
     * @throws OkxCredentialException 该用户没有可用凭证
     */
    public JsonNode placeOrder(long userId, String instId, String side, String ordType,
                               String sz, String px, String clOrdId) {
        guardEnabled();
        String pureId = OkxSymbols.toInstId(instId);
        if (!SIDES.contains(side)) {
            throw new IllegalArgumentException("side 只能是 buy/sell：" + side);
        }
        if (!ORD_TYPES.contains(ordType)) {
            throw new IllegalArgumentException("ordType 只能是 market/limit：" + ordType);
        }
        BigDecimal size = positive(sz, "sz");
        BigDecimal max = decimalOrNull(config.getMaxOrderSize());
        if (max != null && max.signum() > 0 && size.compareTo(max) > 0) {
            throw new IllegalArgumentException("下单数量 " + size.toPlainString()
                    + " 超过上限 " + max.toPlainString() + "（okx.max-order-size）");
        }
        BigDecimal price = null;
        if ("limit".equals(ordType)) {
            price = positive(px, "px");
        } else if (px != null && !px.isBlank()) {
            throw new IllegalArgumentException("市价单不要传 px");
        }
        String effectiveClOrdId = (clOrdId == null || clOrdId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : clOrdId.trim();

        // 凭证在任何网络调用之前解析：没有凭证就直接失败，绝不发半截请求
        OkxCredentials creds = credentials.resolve(userId);

        // 风控（阶段 3.4 四道）：单笔金额 + 日累计 + 回撤 + kill switch，下单前拦截
        BigDecimal notional = riskNotional(pureId, ordType, size, price);
        riskControl.checkBeforeOrder(userId, notional);

        // ① 写前日志：必须在任何网络调用之前落库
        CryptoOrder logged = orderLog.createPending(new CryptoOrderService.NewOrder(
                effectiveClOrdId, pureId, "SPOT", side, ordType, price, size, "cash"));
        if (logged != null && !CryptoOrderState.PENDING.getCode().equals(logged.getState())) {
            // 同 clOrdId 已有一笔未完成单，幂等命中，绝不再发一次网络请求
            log.info("[OKX] clOrdId 复用命中未完成单，跳过下单 clOrdId={} state={}",
                    effectiveClOrdId, logged.getState());
            return idempotentHitResponse(logged);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", pureId);
        body.put("tdMode", "cash");
        body.put("side", side);
        body.put("ordType", ordType);
        body.put("sz", size.stripTrailingZeros().toPlainString());
        body.put("clOrdId", effectiveClOrdId);
        if (price != null) {
            body.put("px", price.stripTrailingZeros().toPlainString());
        }

        log.info("[OKX] 下单 {}(模拟盘) userId={} {} {} sz={} px={} clOrdId={}", pureId, userId,
                side, ordType, body.get("sz"), body.get("px"), effectiveClOrdId);

        // ② 发网络
        JsonNode response;
        try {
            response = client.privatePost(creds, "/api/v5/trade/order", body);
        } catch (OkxClient.OkxApiException e) {
            orderLog.markSubmitted(effectiveClOrdId, null, e.getCode(), e.getMessage());
            throw new OkxOrderRejectedException(e.getCode(), e.getMessage());
        }

        // ③ 入口返回后登记 ordId / sCode。被拒时没有 WS 推送，必须在这里定格。
        OkxOrderResponse result = OkxOrderResponse.first(response);
        if (result.sCode() == null) {
            throw new IllegalStateException("OKX 下单响应缺少 sCode，订单状态待核实：" + effectiveClOrdId);
        }
        if (!"0".equals(result.sCode())) {
            orderLog.markSubmitted(effectiveClOrdId, result.ordId(), result.sCode(), result.sMsg());
            throw new OkxOrderRejectedException(result.sCode(), result.sMsg());
        }
        if (!result.accepted()) {
            throw new IllegalStateException("OKX 下单响应缺少 ordId，订单状态待核实：" + effectiveClOrdId);
        }
        orderLog.markSubmitted(effectiveClOrdId, result.ordId(), result.sCode(), result.sMsg());
        riskControl.record(userId, notional);
        return response;
    }

    /**
     * 撤单。撤单不产生新订单行，但会先确认本地有这笔单；
     * 状态由 WS {@code orders} 频道的 {@code canceled} 推送推进，不在这里直接改。
     */
    public JsonNode cancelOrder(long userId, String instId, String ordId) {
        guardEnabled();
        CryptoOrder local = orderLog.findByOrdId(ordId);
        if (local == null) {
            log.warn("[OKX] 撤单在本地订单表找不到 ordId={}（可能是外部创建的单），仍向交易所发起撤单", ordId);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instId", OkxSymbols.toInstId(instId));
        body.put("ordId", ordId);
        return client.privatePost(credentials.resolve(userId), "/api/v5/trade/cancel-order", body);
    }

    /** 当前配置与总闸状态，供前端/运维自检。凭证是按用户的，因此这里只报「来源」不报值。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", config.isTradeEnabled());
        m.put("simulated", config.isSimulated());
        m.put("credentialSource", "per-user:data_db.okx_account");
        m.put("baseUrl", config.getBaseUrl());
        m.put("maxOrderSize", config.getMaxOrderSize());
        m.put("symbols", config.getSymbols());
        m.put("bars", config.getBars());
        return m;
    }

    /** 幂等命中时给调用方一个形状与 OKX 响应一致的回包，明确标注 skipped。 */
    private static JsonNode idempotentHitResponse(CryptoOrder logged) {
        ArrayNode data = NODES.arrayNode();
        ObjectNode item = data.addObject();
        item.put("clOrdId", logged.getClOrdId());
        item.put("ordId", logged.getOrdId());
        item.put("sCode", "0");
        item.put("sMsg", "duplicate clOrdId: existing open order, request skipped");
        return data;
    }

    /** 计算本单金额：限价 = size × px；市价 = size × 最新成交价。 */
    private BigDecimal riskNotional(String pureId, String ordType, BigDecimal size, BigDecimal price) {
        if ("limit".equals(ordType)) {
            return size.multiply(price);
        }
        BigDecimal last = latestPrice(pureId);
        if (last == null) {
            throw new IllegalStateException("无法获取最新价，市价单无法做金额风控：" + pureId);
        }
        return size.multiply(last);
    }

    /** 最新成交价（公共 ticker）。拿不到返回 null。 */
    private BigDecimal latestPrice(String instId) {
        try {
            JsonNode resp = client.publicGet("/api/v5/market/ticker", Map.of("instId", instId));
            JsonNode first = resp != null && resp.isArray() && !resp.isEmpty() ? resp.get(0) : null;
            if (first != null && first.isObject()) {
                String last = first.path("last").asText(null);
                if (last != null && !last.isBlank()) {
                    return new BigDecimal(last);
                }
            }
        } catch (Exception e) {
            log.warn("查询最新价失败 {}: {}", instId, e.getMessage());
        }
        return null;
    }

    public void assertTradingEnabled() {
        guardEnabled();
    }

    private void guardEnabled() {
        if (!config.isTradeEnabled()) {
            throw new IllegalStateException("OKX 下单已关闭：把 okx.trade-enabled 打开后才允许交易");
        }
    }

    private static BigDecimal positive(String raw, String field) {
        BigDecimal d = decimalOrNull(raw);
        if (d == null || d.signum() <= 0) {
            throw new IllegalArgumentException(field + " 必须是正数：" + raw);
        }
        return d;
    }

    private static BigDecimal decimalOrNull(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法数字：" + raw);
        }
    }
}
