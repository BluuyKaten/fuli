package com.fuli.data.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.crypto.entity.CryptoOrder;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxCredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单对账（实施计划 P4）：把交易所 {@code orders-pending} 与本地未完成单做一次只读比对。
 *
 * <p><b>只报告、不自动修数据</b>。断网恢复后本地与交易所可能同时存在「一边有一边没有」的单，
 * 自动补登或自动改状态很容易把状态机搞乱；先给人一份差异清单，修复动作由运维显式执行。
 *
 * <p>孤儿单（本地 {@code pending}、即已落库但未发网络）单独列出，
 * 这是写前日志最该被检查的场景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxOrderReconciler {

    private final OkxClient client;

    private final OkxCredentialService credentials;

    private final CryptoOrderService orderService;

    public record Result(int exchangeCount, int localCount,
                         List<String> exchangeOnly, List<String> localOnly,
                         List<Long> orphanIds) {
        public boolean clean() {
            return exchangeOnly.isEmpty() && localOnly.isEmpty() && orphanIds.isEmpty();
        }
    }

    /**
     * @param userId 以谁的账户对账；凭证按他归属，不存在「用系统账户对所有人的账」
     */
    public Result reconcile(long userId, String instType, int limit) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("instType", instType == null || instType.isBlank() ? "SPOT" : instType);
        JsonNode pending = client.privateGet(credentials.resolve(userId),
                "/api/v5/trade/orders-pending", q);

        Set<String> exchangeIds = new LinkedHashSet<>();
        if (pending != null && pending.isArray()) {
            for (JsonNode n : pending) {
                String ordId = n.path("ordId").asText("");
                if (!ordId.isEmpty()) {
                    exchangeIds.add(ordId);
                }
            }
        }

        List<CryptoOrder> local = orderService.findOpenOrders(limit);
        Set<String> localIds = new LinkedHashSet<>();
        for (CryptoOrder order : local) {
            if (order.getOrdId() != null) {
                localIds.add(order.getOrdId());
            }
        }

        List<String> exchangeOnly = new ArrayList<>(exchangeIds);
        exchangeOnly.removeAll(localIds);
        List<String> localOnly = new ArrayList<>(localIds);
        localOnly.removeAll(exchangeIds);

        List<Long> orphanIds = new ArrayList<>();
        for (CryptoOrder order : orderService.findOrphans()) {
            orphanIds.add(order.getId());
        }

        Result result = new Result(exchangeIds.size(), localIds.size(),
                exchangeOnly, localOnly, orphanIds);
        if (!result.clean()) {
            log.warn("[OKX-RECON] 差异：交易所独有={} 本地独有={} 孤儿单={}",
                    exchangeOnly, localOnly, orphanIds);
        }
        return result;
    }
}
