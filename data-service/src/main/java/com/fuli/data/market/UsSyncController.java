package com.fuli.data.market;

import com.fuli.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 美股行情同步端点。
 *
 * <p>经网关走 {@code /api/data/us/**}（需 JWT）；内网直连 8084 与 Tushare/加密同步一致。
 *
 * <p>单个同步返回根数；批量同步返回每个标的的结果（逐个 try/catch，一只失败不影响其余），
 * 前端「美股」标签页用批量接口一次录入一组标的。
 */
@Slf4j
@RestController
@RequestMapping("/data/us")
public class UsSyncController {

    /** 批量同步一次最多多少个标的：每个要 2~3 次 HTTP，再多容易把请求拖成分钟级。 */
    private static final int MAX_BATCH = 30;

    private final UsSyncService usSyncService;

    public UsSyncController(UsSyncService usSyncService) {
        this.usSyncService = usSyncService;
    }

    @PostMapping("/sync")
    public Result<Integer> sync(@RequestBody Map<String, String> params) {
        try {
            String symbol = params.get("symbol");
            if (symbol == null || symbol.isBlank()) {
                return Result.error(400, "缺少 symbol（如 AAPL.US）");
            }
            return Result.success(usSyncService.sync(symbol));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("美股同步失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }

    /**
     * 批量同步。
     *
     * <p>body：{@code {"symbols":["AAPL.US","MSFT.US"]}}，也接受逗号分隔字符串
     * {@code {"symbols":"AAPL.US,MSFT.US"}}（前端输入框直接粘贴时省一次转换）。
     */
    @PostMapping("/sync-batch")
    public Result<Map<String, Object>> syncBatch(@RequestBody Map<String, Object> params) {
        List<String> symbols = parseSymbols(params.get("symbols"));
        if (symbols.isEmpty()) {
            return Result.error(400, "缺少 symbols（如 [\"AAPL.US\",\"MSFT.US\"]）");
        }
        if (symbols.size() > MAX_BATCH) {
            return Result.error(400, "一次最多同步 " + MAX_BATCH + " 个标的，请分批提交");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        for (String symbol : symbols) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            try {
                int count = usSyncService.sync(symbol);
                row.put("success", true);
                row.put("count", count);
                ok++;
            } catch (IllegalArgumentException e) {
                row.put("success", false);
                row.put("message", e.getMessage());
            } catch (Exception e) {
                log.warn("美股批量同步失败: {} ({})", symbol, e.getMessage());
                row.put("success", false);
                row.put("message", e.getMessage());
            }
            results.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", usSyncService.source());
        data.put("total", symbols.size());
        data.put("succeeded", ok);
        data.put("results", results);
        return Result.success(data);
    }

    /** 当前生效的美股数据源（tencent / eastmoney），排障时先看这个。 */
    @GetMapping("/source")
    public Result<String> source() {
        return Result.success(usSyncService.source());
    }

    private static List<String> parseSymbols(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    addSymbol(out, item.toString());
                }
            }
        } else if (raw instanceof String text) {
            for (String part : text.split("[,，\\s]+")) {
                addSymbol(out, part);
            }
        }
        return out;
    }

    private static void addSymbol(List<String> out, String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase();
        if (!symbol.isEmpty() && !out.contains(symbol)) {
            out.add(symbol);
        }
    }
}
