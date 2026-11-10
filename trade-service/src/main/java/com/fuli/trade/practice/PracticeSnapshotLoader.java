package com.fuli.trade.practice;

import com.fuli.common.api.feign.DataFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class PracticeSnapshotLoader {
    public static final String DATA_ERROR = "所选区间行情无效或存在价格口径断层，请同步并核查未复权日线后重试";
    private static final Pattern A_SHARE = Pattern.compile("(?:(?:600|601|603|605|688|689)\\d{3}\\.SH|(?:000|001|002|003|300|301)\\d{3}\\.SZ|(?:43|83|87|88|92)\\d{4}\\.BJ)");
    private final DataFeignClient data;

    public PracticeSnapshotLoader(DataFeignClient data) {
        this.data = data;
    }

    public record Inputs(String stockCode, LocalDate start, LocalDate end, BigDecimal cash) {}

    public Inputs inputs(PracticeDtos.CreateRequest request) {
        if (request == null || request.stockCode() == null) throw bad("请输入A股代码");
        String code = request.stockCode().trim().toUpperCase(Locale.ROOT);
        // Plain chart codes are normalized to the data_db exchange-qualified form.
        if (code.matches("\\d{6}")) {
            code += code.startsWith("6") ? ".SH" : code.startsWith("0") || code.startsWith("3") ? ".SZ" : ".BJ";
        }
        if (!A_SHARE.matcher(code).matches()) throw bad("仅支持合法A股代码（.SH/.SZ/.BJ）");
        LocalDate start = inputDate(request.startDate());
        LocalDate end = inputDate(request.endDate());
        if (start.isBefore(LocalDate.of(1990, 1, 1)) || !end.isAfter(start)
                || end.isAfter(LocalDate.now()) || end.isAfter(start.plusYears(5))) {
            throw bad("请选择1990年后、截至今日且不超过5年的起止区间，结束日期须晚于起点");
        }
        BigDecimal cash = request.initialCash() == null ? new BigDecimal("100000.00") : request.initialCash();
        if (cash.compareTo(new BigDecimal("100.00")) < 0 || cash.compareTo(new BigDecimal("1000000000.00")) > 0
                || cash.stripTrailingZeros().scale() > 2) {
            throw bad("初始资金须为100至1000000000元，精确到分");
        }
        return new Inputs(code, start, end, cash.setScale(2));
    }

    public PracticeState.Snapshot load(Inputs input) {
        LocalDate from = input.start().minusYears(3);
        List<Map<String, Object>> rows;
        try {
            rows = data.getDailyData(input.stockCode(), from.format(DateTimeFormatter.BASIC_ISO_DATE),
                    input.end().format(DateTimeFormatter.BASIC_ISO_DATE), null);
        } catch (RuntimeException e) {
            // Upstream error text can contain future dates/prices; never forward it.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "日线数据暂不可用，请先同步行情后重试");
        }
        if (rows == null || rows.isEmpty()) throw bad("没有可用的已同步日线，请先同步行情；无法区分停牌与数据缺失");
        if (rows.size() > 3000) throw bad(DATA_ERROR);
        try {
            List<DatedRow> dated = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                LocalDate date = inputDate(String.valueOf(row.get("tradeDate")));
                if (date.isBefore(from) || date.isAfter(input.end())) throw new IllegalArgumentException();
                if (row.get("stockCode") != null && !input.stockCode().equals(row.get("stockCode").toString())) {
                    throw new IllegalArgumentException();
                }
                dated.add(new DatedRow(date, row));
            }
            dated.sort(Comparator.comparing(DatedRow::date));
            for (int i = 1; i < dated.size(); i++) {
                if (dated.get(i).date().equals(dated.get(i - 1).date())) throw new IllegalArgumentException();
            }
            int anchor = -1;
            for (int i = 0; i < dated.size(); i++) {
                if (!dated.get(i).date().isAfter(input.start())) anchor = i;
            }
            if (anchor < 0 || anchor == dated.size() - 1) {
                throw bad("起点需有已同步背景行情，终点前至少需要一根后续日线；无法区分停牌与数据缺失");
            }
            int first = Math.max(0, anchor - 119);
            List<PracticeState.FrozenBar> bars = new ArrayList<>();
            for (int i = first; i < dated.size(); i++) {
                DatedRow row = dated.get(i);
                Map<String, Object> value = row.row();
                BigDecimal open = positive(value, "openPrice");
                BigDecimal high = positive(value, "highPrice");
                BigDecimal low = positive(value, "lowPrice");
                BigDecimal close = positive(value, "closePrice");
                BigDecimal preClose = positive(value, "preClose");
                BigDecimal volume = decimal(value, "vol");
                if (volume.signum() < 0 || high.compareTo(low) < 0 || high.compareTo(open) < 0
                        || high.compareTo(close) < 0 || low.compareTo(open) > 0 || low.compareTo(close) > 0) {
                    throw new IllegalArgumentException();
                }
                if (!bars.isEmpty()) {
                    BigDecimal previous = bars.get(bars.size() - 1).close();
                    BigDecimal tolerance = previous.multiply(new BigDecimal("0.005"));
                    BigDecimal deviation = preClose.subtract(previous);
                    // 昨收高于前收盘在未复权口径下不可能出现，只可能是复权口径混用，仍判为断层。
                    if (deviation.compareTo(tolerance) > 0) {
                        throw new IllegalArgumentException();
                    }
                    // 昨收低于前收盘多为除权除息／送转的除权参考价，属正常行情，放行并留痕。
                    if (deviation.negate().compareTo(tolerance) > 0) {
                        log.warn("除权除息缺口放行: {} {} preClose={} 前收盘={}", input.stockCode(), row.date(), preClose, previous);
                    }
                }
                bars.add(new PracticeState.FrozenBar(row.date().toString(), open, high, low, close, volume, preClose));
            }
            return new PracticeState.Snapshot(bars, anchor - first + 1);
        } catch (ResponseStatusException e) {
            // Only our generic missing-range message may cross the boundary.
            if (e.getReason() != null && e.getReason().startsWith("起点需")) throw e;
            throw bad(DATA_ERROR);
        } catch (RuntimeException e) {
            throw bad(DATA_ERROR);
        }
    }

    static LocalDate inputDate(String text) {
        if (text == null || !text.matches("(?:\\d{4}-\\d{2}-\\d{2}|\\d{8})")) throw bad("日期须为有效的yyyy-MM-dd");
        try {
            return LocalDate.parse(text, text.length() == 8 ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw bad("日期须为有效的yyyy-MM-dd");
        }
    }

    private static BigDecimal positive(Map<String, Object> row, String name) {
        BigDecimal value = decimal(row, name);
        if (value.signum() <= 0) throw new IllegalArgumentException();
        return value;
    }

    private static BigDecimal decimal(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value == null) throw new IllegalArgumentException();
        BigDecimal number = new BigDecimal(value.toString());
        if (number.precision() > 24 || number.scale() > 8 || number.abs().compareTo(new BigDecimal("1000000000000")) > 0) {
            throw new IllegalArgumentException();
        }
        return number;
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record DatedRow(LocalDate date, Map<String, Object> row) {}
}
