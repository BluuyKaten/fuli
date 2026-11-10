package com.fuli.data.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.crypto.entity.CryptoAccount;
import com.fuli.data.crypto.mapper.CryptoAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 账户余额快照（ADR-0010 决策 5 的延伸：WS {@code account} 频道落库）。
 *
 * <p>为什么单独一张表而不是塞进订单表：余额是「账户视角的事实」，与订单状态机无关；
 * 混在一起会让订单表的语义变糊，也不方便按币种画曲线。
 *
 * <p>去重靠唯一键 {@code (user_id, ccy, u_time)}：OKX 会定时重推同一份快照，
 * 应用层判断「看起来一样」既脆弱又多余——数据库唯一键是最可靠的判据。
 * 因此 {@link DuplicateKeyException} 在这里是**正常路径**，只记 debug。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoAccountService {

    /** 合成行的币种标记：存 OKX 的 totalEq（全账户折合 USD 权益）。 */
    public static final String TOTAL_CCY = "TOTAL";

    private final CryptoAccountMapper mapper;

    /**
     * 处理一条 {@code account} 推送。
     *
     * @param userId WS 连接所属用户（{@code okx.ws-owner-user-id}）
     * @param node   {@code data[i]}，含 totalEq 与 details[]
     * @return 实际写入的行数（去重后可能为 0）
     */
    public int recordSnapshot(long userId, JsonNode node) {
        if (userId <= 0 || node == null || !node.isObject()) {
            return 0;
        }
        Long uTime = lng(node, "uTime");
        if (uTime == null) {
            // 没有 uTime 就无法去重，宁可不写：写入重复数据比少写更糟
            log.debug("[OKX-ACC] 推送缺 uTime，跳过");
            return 0;
        }
        int written = 0;

        BigDecimal totalEq = dec(node, "totalEq");
        if (totalEq != null) {
            written += insertQuietly(row(userId, TOTAL_CCY, totalEq, null, null, null, null, null, null, null, uTime));
        }

        JsonNode details = node.path("details");
        if (details.isArray()) {
            for (JsonNode d : details) {
                String ccy = text(d, "ccy");
                if (ccy == null) {
                    continue;
                }
                written += insertQuietly(row(userId, ccy,
                        dec(d, "eq"), dec(d, "cashBal"), dec(d, "availBal"), dec(d, "availEq"),
                        dec(d, "frozenBal"), dec(d, "upl"), dec(d, "isoEq"), dec(d, "disEq"), uTime));
            }
        }
        if (written > 0) {
            log.debug("[OKX-ACC] 余额快照写入 {} 行 userId={} uTime={}", written, userId, uTime);
        }
        return written;
    }

    public List<CryptoAccount> latest(long userId) {
        return mapper.selectLatestPerCcy(userId);
    }

    public List<CryptoAccount> history(long userId, String ccy, int limit) {
        int size = limit <= 0 ? 100 : Math.min(limit, 500);
        return mapper.selectHistory(userId, ccy, size);
    }

    /** 保留 90 天，按批删；返回删除行数。 */
    public int purge(LocalDateTime cutoff, int batchSize, int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = mapper.deleteOlderThan(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }

    private int insertQuietly(CryptoAccount row) {
        try {
            mapper.insert(row);
            return 1;
        } catch (DuplicateKeyException duplicate) {
            return 0;   // 定时重推的同一份快照，正常路径
        }
    }

    private static CryptoAccount row(long userId, String ccy, BigDecimal eq, BigDecimal cashBal,
                                     BigDecimal availBal, BigDecimal availEq, BigDecimal frozenBal,
                                     BigDecimal upl, BigDecimal isoEq, BigDecimal disEq, long uTime) {
        CryptoAccount a = new CryptoAccount();
        a.setUserId(userId);
        a.setCcy(ccy);
        a.setEquity(eq);
        a.setCashBal(cashBal);
        a.setAvailBal(availBal);
        a.setAvailEq(availEq);
        a.setFrozenBal(frozenBal);
        a.setUpl(upl);
        a.setIsoEq(isoEq);
        a.setDisEq(disEq);
        a.setUTime(uTime);
        a.setSnapshotAt(LocalDateTime.now());
        return a;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("");
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal dec(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long lng(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
