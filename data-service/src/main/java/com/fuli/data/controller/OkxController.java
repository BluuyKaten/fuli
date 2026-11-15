package com.fuli.data.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.common.api.Result;
import com.fuli.data.okx.OkxAccountIdentity;
import com.fuli.data.okx.OkxCredentialService;
import com.fuli.data.okx.OkxJson;
import com.fuli.data.okx.OkxMarketService;
import com.fuli.data.okx.OkxOrderResponse;
import com.fuli.data.okx.OkxPeriods;
import com.fuli.data.okx.OkxSymbols;
import com.fuli.data.okx.OkxTradeService;
import com.fuli.data.security.OkxAuthz;
import com.fuli.data.security.OkxPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * OKX 接入入口：行情同步 + 交易自检/下单。见 docs/adr/0008、docs/adr/0009、docs/adr/0010。
 *
 * <p>鉴权（决策 8 + ADR-0010）：所有端点先过 {@link com.fuli.data.security.OkxAuthenticationFilter}；
 * 读操作要求有效令牌，写操作额外要求用户级 {@code okx_trade_enabled=1}（服务账号一律 403）。
 * 身份只取服务端 attribute，不接受调用方传入的用户头。
 *
 * <p>凭证（ADR-0010 决策 1）：每个私有调用都用**当前 userId** 从 {@code okx_account} 解密，
 * 这里不接触明文，也不存在「没配就回退到环境变量」的分支。
 *
 * <p>和 {@code /market/bars} 一样走 {@code Result} 包装。业务错误一律 400 带中文原因——
 * 「交易开关没开」「这个用户没有交易权限」和「签名错了」是三种完全不同的故障，
 * 不能让调用方看到一个笼统的 500；但 401/403 必须原样交给 MVC，不能被 business error 吞成 400。
 */
@RestController
@RequestMapping("/data/okx")
@RequiredArgsConstructor
public class OkxController {

    private final OkxMarketService marketService;

    private final OkxTradeService tradeService;

    private final OkxCredentialService credentialService;

    private final OkxAccountIdentity accountIdentity;

    private final OkxAuthz authz;

    /** 同步 SPOT 标的基础信息（写 instrument，market=CRYPTO）。 */
    @PostMapping("/sync/instruments")
    public ResponseEntity<Result<Integer>> syncInstruments(HttpServletRequest request) {
        return execute(request, "syncInstruments", true, "-", marketService::syncInstruments);
    }

    /**
     * 拉某标的某周期的最新一页 K 线。
     *
     * @param symbol BTC-USDT.CRYPTO 或裸 BTC-USDT
     * @param period 受控集合 1m/5m/15m/30m/1h/4h/1d/1w/1mo
     */
    @PostMapping("/sync/candles")
    public ResponseEntity<Result<Integer>> syncCandles(HttpServletRequest request,
                                                        @RequestBody Map<String, String> body) {
        return execute(request, "syncCandles", true, detailOf(body, "symbol", "period"), () -> {
            String symbol = require(body, "symbol");
            String period = body.getOrDefault("period", OkxPeriods.supported().get(6));
            return marketService.syncLatest(symbol, period);
        });
    }

    /** 按配置（okx.symbols × okx.bars）把所有标的的最新页拉一遍。 */
    @PostMapping("/sync/all")
    public ResponseEntity<Result<Integer>> syncAll(HttpServletRequest request) {
        return execute(request, "syncAll", true, "-", marketService::syncConfigured);
    }

    /**
     * 往回补历史。
     *
     * @param body symbol / period / days（默认 30，往回补几天）/ maxPages（默认 20，安全上限）
     */
    @PostMapping("/backfill")
    public ResponseEntity<Result<Integer>> backfill(HttpServletRequest request,
                                                    @RequestBody Map<String, String> body) {
        return execute(request, "backfill", true, detailOf(body, "symbol", "period", "days", "maxPages"), () -> {
            String symbol = require(body, "symbol");
            String period = body.getOrDefault("period", "1d");
            int days = intOf(body.get("days"), 30);
            int maxPages = intOf(body.get("maxPages"), 20);
            long startTs = Instant.now().getEpochSecond() - days * 86400L;
            return marketService.backfill(symbol, period, startTs, maxPages);
        });
    }

    /**
     * 当前配置、四道闸与**本人**凭证状态，部署后先看这个。
     *
     * <p>{@code credentialConfigured} 是「我这个账号配了 Key 没有」，
     * 而不是「服务端有没有全局 Key」——后者已经不存在了。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(HttpServletRequest request) {
        OkxPrincipal principal = authz.requireRead(request, "status");
        Map<String, Object> m = new LinkedHashMap<>(tradeService.status());
        m.put("configuredSymbols", marketService.configuredSymbols());
        m.put("supportedPeriods", OkxPeriods.supported());
        Long accountUserId = accountIdentity.accountUserIdOrNull(principal);
        m.put("principal", principal.display());
        // 服务账号没有自己的账户，这里显示它代表谁（未配置则为 null）
        m.put("accountUserId", accountUserId);
        m.put("credentialConfigured", accountUserId != null && credentialService.hasEnabled(accountUserId));
        return Result.success(m);
    }

    // ---------------- 交易（默认关闭） ----------------

    @GetMapping("/trade/config")
    public ResponseEntity<Result<Object>> accountConfig(HttpServletRequest request) {
        return execute(request, "accountConfig", false, "-",
                principal -> OkxJson.toPlain(tradeService.accountConfig(accountUserId(principal))));
    }

    @GetMapping("/trade/balance")
    public ResponseEntity<Result<Object>> balance(HttpServletRequest request,
                                                    @RequestParam(value = "ccy", required = false) String ccy) {
        return execute(request, "balance", false, "ccy=" + safe(ccy),
                principal -> OkxJson.toPlain(tradeService.balance(accountUserId(principal), ccy)));
    }

    @GetMapping("/trade/positions")
    public ResponseEntity<Result<Object>> positions(HttpServletRequest request,
                                                      @RequestParam(value = "instType", required = false) String instType) {
        return execute(request, "positions", false, "instType=" + safe(instType),
                principal -> OkxJson.toPlain(tradeService.positions(accountUserId(principal), instType)));
    }

    @GetMapping("/trade/orders-pending")
    public ResponseEntity<Result<Object>> pendingOrders(HttpServletRequest request,
                                                          @RequestParam(value = "instType", required = false) String instType) {
        return execute(request, "pendingOrders", false, "instType=" + safe(instType),
                principal -> OkxJson.toPlain(tradeService.pendingOrders(accountUserId(principal), instType)));
    }

    /**
     * 下单。body: symbol / side / ordType / sz / px / clOrdId。
     *
     * <p>单独写一份而不是套 {@link #execute} 模板：下单的审计要带结构化的
     * instId / side / sz / px / ordId，才能在 {@code okx_audit_log} 里按订单号直接检索。
     */
    @PostMapping("/trade/order")
    public ResponseEntity<Result<Object>> placeOrder(HttpServletRequest request,
                                                       @RequestBody Map<String, String> body) {
        String instId = "";
        OkxPrincipal principal;
        try {
            instId = safe(OkxSymbols.toInstId(require(body, "symbol")));
        } catch (RuntimeException ignored) {
            // 符号非法时仍然先把权限走完，保证「未授权」优先于「参数错」返回 403 而不是 400
        }
        try {
            principal = authz.requireWrite(request, "placeOrder",
                    detailOf(body, "symbol", "side", "ordType", "sz", "px", "clOrdId"));
        } catch (ResponseStatusException e) {
            throw e;
        }

        try {
            JsonNode data = tradeService.placeOrder(principal.userId(),
                    require(body, "symbol"),
                    body.getOrDefault("side", ""),
                    body.getOrDefault("ordType", "limit"),
                    require(body, "sz"),
                    body.get("px"),
                    body.get("clOrdId"));
            authz.auditTradeResult("placeOrder", principal, "SUCCESS", instId,
                    body.get("symbol"), body.get("side"),
                    decimalOrNull(body.get("sz")), decimalOrNull(body.get("px")),
                    firstOrdId(data), body.get("clOrdId"), "ordType=" + body.get("ordType"));
            return ResponseEntity.ok(Result.success(OkxJson.toPlain(data)));
        } catch (Exception e) {
            authz.auditTradeResult("placeOrder", principal, "FAILED", instId,
                    body == null ? null : body.get("symbol"),
                    body == null ? null : body.get("side"),
                    decimalOrNull(body == null ? null : body.get("sz")), null,
                    null, null, "err=" + safe(e.getMessage()));
            return badRequest(e);
        }
    }

    /** 撤单。body: symbol / ordId。 */
    @PostMapping("/trade/cancel")
    public ResponseEntity<Result<Object>> cancel(HttpServletRequest request,
                                                   @RequestBody Map<String, String> body) {
        return execute(request, "cancelOrder", true, detailOf(body, "symbol", "ordId"),
                principal -> OkxJson.toPlain(tradeService.cancelOrder(principal.userId(),
                        require(body, "symbol"), require(body, "ordId"))));
    }

    /**
     * 鉴权 + 业务执行 + 成功/失败审计的统一模板。
     * 鉴权放在业务 try 之外，保证 401/403 不被 {@link #badRequest} 改写成 400。
     */
    private <T> ResponseEntity<Result<T>> execute(HttpServletRequest request, String action,
                                                   boolean write, String detail,
                                                   java.util.function.Function<OkxPrincipal, T> work) {
        OkxPrincipal principal;
        try {
            principal = write ? authz.requireWrite(request, action, detail)
                    : authz.requireRead(request, action);
        } catch (ResponseStatusException e) {
            throw e;
        }
        // 读端点的审计 scope 必须是 orders:read：记成 write 会让「谁在改单」的统计掺进查询
        String scope = write ? OkxPrincipal.SCOPE_WRITE : OkxPrincipal.SCOPE_READ;
        try {
            T data = work.apply(principal);
            authz.auditResult(action, scope, principal, "SUCCESS", detail);
            return ResponseEntity.ok(Result.success(data));
        } catch (Exception e) {
            authz.auditResult(action, scope, principal, "FAILED", detail + " err=" + safe(e.getMessage()));
            return badRequest(e);
        }
    }

    /** 同步类端点不需要 userId，用这个重载保持调用点简洁。 */
    private <T> ResponseEntity<Result<T>> execute(HttpServletRequest request, String action,
                                                   boolean write, String detail, Supplier<T> work) {
        return execute(request, action, write, detail, principal -> work.get());
    }

    /** 私有请求用谁的账户：用户是他自己；服务账号用代理身份（写操作到不了这里）。 */
    private long accountUserId(OkxPrincipal principal) {
        return accountIdentity.requireAccountUserId(principal);
    }

    private static String firstOrdId(JsonNode data) {
        String ordId = OkxOrderResponse.from(data).ordId();
        return ordId == null || ordId.isBlank() ? null : ordId;
    }

    private static BigDecimal decimalOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String require(Map<String, String> body, String key) {
        String v = body == null ? null : body.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return v;
    }

    private static int intOf(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("不是合法整数：" + raw);
        }
    }

    /** 审计明细只摘取白名单字段并清洗，绝不整包打印请求体，也绝不记录凭证。 */
    private static String detailOf(Map<String, String> body, String... keys) {
        if (body == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            String value = body.get(key);
            if (value != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(key).append('=').append(safe(value));
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** 去换行 + 限长，避免用户输入污染结构化日志。 */
    private static String safe(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() > 120 ? cleaned.substring(0, 120) + "..." : cleaned;
    }

    private static <T> ResponseEntity<Result<T>> badRequest(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.badRequest().body(Result.error(HttpStatus.BAD_REQUEST.value(), msg));
    }
}
