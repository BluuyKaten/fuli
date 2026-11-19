package com.fuli.data.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.crypto.CryptoOrderService;
import com.fuli.data.crypto.CryptoOrderState;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxOrderResponse;
import com.fuli.data.okx.OkxCredentialException;
import com.fuli.data.okx.OkxCredentialService;
import com.fuli.data.okx.OkxCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P6：七模块能力的统一执行器。
 *
 * <p>读能力直接按 catalog 的路径发请求；**写能力一律先落写前日志再发网络**，
 * 与 {@code OkxTradeService.placeOrder} 同一套顺序，避免 P6 新开一条绕过状态机的路径。
 *
 * <p>本服务不判断用户权限——那是 controller 层 {@code OkxAuthz} 的职责；
 * 调用方必须先通过读写 scope 校验才能进来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxCapabilityService {

    private final OkxClient client;

    private final CryptoOrderService orderLog;

    private final OkxCredentialService credentials;

    /**
     * @param userId 当前调用者，凭证按他归属；服务账号没有 userId，只允许读不签名（公共）能力
     */
    public JsonNode execute(String name, Map<String, String> query, Map<String, Object> body,
                            Long userId) {
        OkxModuleCatalog.Capability cap = OkxModuleCatalog.require(name);
        if (!"GET".equals(cap.method()) && !"POST".equals(cap.method())) {
            throw new IllegalArgumentException("不支持的 HTTP 方法：" + cap.method());
        }
        if (cap.write()) {
            return executeWrite(cap, body, userId);
        }
        if (!cap.signed()) {
            return client.publicGet(cap.path(), query);
        }
        return client.privateGet(requireCredentials(userId), cap.path(), query);
    }

    private JsonNode executeWrite(OkxModuleCatalog.Capability cap, Map<String, Object> body,
                                  Long userId) {
        // 凭证最先解析：没有可用凭证时连写前日志都不该留，否则库里会多出一堆永远不会发出去的 pending
        OkxCredentials creds = requireCredentials(userId);

        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        String instId = payload.get("instId") == null ? "" : payload.get("instId").toString();
        String clOrdId = payload.containsKey("clOrdId")
                ? payload.get("clOrdId").toString()
                : UUID.randomUUID().toString().replace("-", "");
        if (!payload.containsKey("clOrdId")) {
            payload.put("clOrdId", clOrdId);
        }
        // 下单类端点才需要 SPOT/cash 口径；杠杆/机器人等由调用方在 body 里自带字段
        payload.putIfAbsent("tdMode", "cash");

        String sz = payload.get("sz") == null ? "0" : payload.get("sz").toString();
        String px = payload.get("px") == null ? null : payload.get("px").toString();
        CryptoOrder logged = orderLog.createPending(new CryptoOrderService.NewOrder(
                clOrdId, instId, cap.module(), "buy", "limit",
                decimal(px), decimal(sz), String.valueOf(payload.get("tdMode"))));
        if (logged != null && !CryptoOrderState.PENDING.getCode().equals(logged.getState())) {
            log.info("[OKX-CAP] {} 幂等命中，跳过网络请求 clOrdId={}", cap.name(), clOrdId);
            var data = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            data.addObject().put("sCode", "0").put("sMsg", "duplicate clOrdId, skipped");
            return data;
        }

        // 凭证已在方法入口解析（见上），这里不再重复取
        log.info("[OKX-CAP] 写能力 {} path={} userId={} clOrdId={}", cap.name(), cap.path(),
                userId, clOrdId);
        JsonNode response;
        try {
            response = client.privatePost(creds, cap.path(), payload);
        } catch (OkxClient.OkxApiException e) {
            orderLog.markSubmitted(clOrdId, null, e.getCode(), e.getMessage());
            throw new com.fuli.data.okx.OkxOrderRejectedException(e.getCode(), e.getMessage());
        }

        OkxOrderResponse result = OkxOrderResponse.first(response);
        if (result.sCode() == null) {
            throw new IllegalStateException("OKX 写能力响应缺少 sCode，订单状态待核实：" + clOrdId);
        }
        orderLog.markSubmitted(clOrdId, result.ordId(), result.sCode(), result.sMsg());
        if (result.sCode() != null && !"0".equals(result.sCode())) {
            throw new com.fuli.data.okx.OkxOrderRejectedException(result.sCode(), result.sMsg());
        }
        return response;
    }

    /** 服务账号没有 userId，私有能力一律拒绝；这里给出明确原因而不是 NPE。 */
    private OkxCredentials requireCredentials(Long userId) {
        if (userId == null || userId <= 0) {
            throw new OkxCredentialException("服务账号没有账户身份，无法调用需要签名的 OKX 私有能力");
        }
        return credentials.resolve(userId);
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return new BigDecimal("0");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return new BigDecimal("0");
        }
    }
}
