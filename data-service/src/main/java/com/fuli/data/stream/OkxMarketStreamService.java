package com.fuli.data.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.crypto.entity.CryptoBookSnapshot;
import com.fuli.data.crypto.mapper.CryptoBookSnapshotMapper;
import com.fuli.data.okx.ws.OkxWsClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * P7 / P8：公共行情与深度频道的处理。
 *
 * <p>两条硬约束（ADR-0009）：
 * <ul>
 *   <li>{@code tickers} / {@code candle*} 只做实时视图，<b>绝不写 bar</b>——
 *       bar 是「已完结事实」表，未完结那根的 OHLC 还在跳（ADR-0008 决策 5）；</li>
 *   <li>{@code books*} 原始 10ms/100ms 帧只在内存缓冲里，经 {@link OkxDownsampler}
 *       按 1 秒稀疏化后才写 {@code crypto_book_snapshot}。</li>
 * </ul>
 *
 * <p>本类与 P4 的 {@code OkxWsMessageDispatcher} 互不相交：那个处理私有频道，
 * 这个处理公共频道，都注册到同一个 {@code OkxWsClient}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxMarketStreamService implements Consumer<String> {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 稀疏快照间隔：1 秒一帧，远低于 10ms 原始频率。 */
    private static final long SNAPSHOT_INTERVAL_MS = 1000L;

    private final OkxWsClient wsClient;

    private final CryptoBookSnapshotMapper bookMapper;

    private final OkxDownsampler downsampler = new OkxDownsampler(SNAPSHOT_INTERVAL_MS);

    @PostConstruct
    public void register() {
        wsClient.addHandler(this);
    }

    public long droppedFrames() {
        return downsampler.droppedCount();
    }

    @Override
    public void accept(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (Exception e) {
            return;  // 坏帧由 P4 dispatcher 记日志，这里不重复
        }
        if (root == null || !root.isObject() || root.has("event")) {
            return;
        }
        String channel = root.path("arg").path("channel").asText("");
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return;
        }
        if (channel.startsWith("books") || channel.startsWith("bbo")) {
            onBook(channel, root.path("arg"), data.get(0));
        } else if (channel.startsWith("candle") || channel.equals("tickers")) {
            // 实时视图：只记 debug，绝不写 bar
            log.debug("[OKX-STREAM] {} 推送 {} 条，仅实时视图，不写 bar", channel, data.size());
        }
    }

    private void onBook(String channel, JsonNode arg, JsonNode snapshot) {
        String instId = arg.path("instId").asText("");
        if (instId.isEmpty()) {
            return;
        }
        long tsMs;
        try {
            tsMs = Long.parseLong(snapshot.path("ts").asText("0"));
        } catch (NumberFormatException e) {
            return;
        }
        if (!downsampler.shouldKeep(tsMs)) {
            return;
        }
        CryptoBookSnapshot row = new CryptoBookSnapshot();
        row.setInstId(instId);
        row.setTsMs(tsMs);
        row.setSeqId(snapshot.hasNonNull("seqId") ? snapshot.path("seqId").asLong() : null);
        row.setChecksum(snapshot.hasNonNull("checksum")
                ? snapshot.path("checksum").asText() : null);
        row.setBids(snapshot.path("bids").toString());
        row.setAsks(snapshot.path("asks").toString());
        try {
            bookMapper.insert(row);
            log.debug("[OKX-STREAM] {} 稀疏快照落库 ts={} 累计丢弃={}", channel, tsMs, droppedFrames());
        } catch (Exception e) {
            // 同一毫秒重复帧会撞主键；稀疏快照丢了不致命，不打断连接
            log.debug("[OKX-STREAM] 稀疏快照写入跳过：{}", e.getMessage());
        }
    }
}
