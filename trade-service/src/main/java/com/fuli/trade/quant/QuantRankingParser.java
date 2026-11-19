package com.fuli.trade.quant;

import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析大智慧复制或导出的制表符、CSV 文本。 */
@Component
public class QuantRankingParser {
    public static final String POPULARITY_METRIC = "人气{wyz}";
    private static final List<String> CODE_HEADERS = List.of("代码", "股票代码", "证券代码", "股票", "code", "stockcode");
    private static final List<String> NAME_HEADERS = List.of("名称", "股票名称", "股票简称", "简称", "name", "stockname");
    private static final Pattern POPULARITY_HEADER = Pattern.compile("人气(?:\\{wyz\\}|\\[wyz\\]|\\(wyz\\)|｛wyz｝|［wyz］|（wyz）|【wyz】)");
    private static final Pattern SOURCE_DATE_HEADER = Pattern.compile("(?:日期|数据日期|交易日期|交易日)?[：:]?(\\d{4})[/.-](\\d{1,2})[/.-](\\d{1,2})");
    private static final Set<String> UNCALCULATED_VALUES = Set.of("", "-", "--", "—");

    public record ParsedRanking(List<QuantRankingDtos.NormalizedRow> rows, int skippedRows) {
    }

    /** 读取导出字节，优先 UTF-8，检测到乱码时回退 GBK。 */
    public String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            return Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    public List<QuantRankingDtos.NormalizedRow> parse(String text, Map<String, String> mapping) {
        return parseResult(text, mapping).rows();
    }

    public ParsedRanking parseResult(String text, Map<String, String> mapping) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("排行文本不能为空");
        }
        String normalizedText = text.replace("\uFEFF", "");
        String[] lines = normalizedText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int headerLine = firstNonBlankLine(lines);
        if (headerLine < 0) {
            throw new IllegalArgumentException("排行文本没有表头");
        }
        char delimiter = chooseDelimiter(lines[headerLine]);
        List<String> headers = splitLine(lines[headerLine], delimiter);
        if (headers.isEmpty()) {
            throw new IllegalArgumentException("排行文本没有有效表头");
        }
        List<String> normalizedHeaders = headers.stream().map(this::normalizeHeader).toList();
        int codeIndex = findColumn("code", mapping, headers, normalizedHeaders, CODE_HEADERS);
        int nameIndex = findColumn("name", mapping, headers, normalizedHeaders, NAME_HEADERS);
        int valueIndex = findPopularityColumn(mapping, headers);
        requireColumn(codeIndex, "代码", headers);
        requireColumn(nameIndex, "名称", headers);
        requireColumn(valueIndex, POPULARITY_METRIC, headers);

        List<String> rawHeaders = uniqueRawHeaders(headers);
        // 同一份导出中偶尔会重复出现标的，保留最后一行，避免明细唯一键冲突。
        Map<String, QuantRankingDtos.NormalizedRow> deduplicated = new LinkedHashMap<>();
        int skippedRows = 0;
        for (int lineNumber = headerLine + 1; lineNumber < lines.length; lineNumber++) {
            if (!StringUtils.hasText(lines[lineNumber])) {
                continue;
            }
            List<String> cells = splitLine(lines[lineNumber], delimiter);
            if (cells.size() < headers.size()) {
                throw new IllegalArgumentException("第" + (lineNumber + 1) + "行列数不足，请检查导出分隔符");
            }
            String code = cleanCode(cells.get(codeIndex));
            String name = cells.get(nameIndex).trim();
            if (!StringUtils.hasText(code) || !StringUtils.hasText(name)) {
                throw new IllegalArgumentException("第" + (lineNumber + 1) + "行代码或名称为空");
            }
            String popularity = cells.get(valueIndex).strip();
            if (UNCALCULATED_VALUES.contains(popularity)) {
                skippedRows++;
                deduplicated.remove(code);
                continue;
            }
            BigDecimal metricValue = parseDecimal(popularity, POPULARITY_METRIC, lineNumber + 1);
            Map<String, String> raw = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                raw.put(rawHeaders.get(i), cells.get(i).trim());
            }
            deduplicated.put(code, new QuantRankingDtos.NormalizedRow(code, name, POPULARITY_METRIC, metricValue, null,
                    Collections.unmodifiableMap(raw)));
        }
        if (deduplicated.isEmpty()) {
            throw new IllegalArgumentException(skippedRows > 0
                    ? "排行文本没有已计算的“" + POPULARITY_METRIC + "”数据，未计算行数：" + skippedRows
                    : "排行文本没有有效数据行");
        }
        List<QuantRankingDtos.NormalizedRow> sorted = deduplicated.values().stream()
                .sorted(Comparator.comparing(QuantRankingDtos.NormalizedRow::metricValue).reversed()
                        .thenComparing(QuantRankingDtos.NormalizedRow::code))
                .toList();
        List<QuantRankingDtos.NormalizedRow> ranked = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            QuantRankingDtos.NormalizedRow row = sorted.get(i);
            ranked.add(new QuantRankingDtos.NormalizedRow(row.code(), row.name(), row.metric(),
                    row.metricValue(), i + 1, row.rawFields()));
        }
        return new ParsedRanking(ranked, skippedRows);
    }

    /** 为同名指标生成独立键，保留导出表的每一列及其顺序。 */
    private List<String> uniqueRawHeaders(List<String> headers) {
        Set<String> reserved = new HashSet<>(headers.stream().map(String::trim).toList());
        Set<String> used = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String header : headers) {
            String base = header.trim();
            String key = base;
            if (used.contains(key)) {
                int suffix = 2;
                do {
                    key = base + "(" + suffix++ + ")";
                } while (reserved.contains(key) || used.contains(key));
            }
            used.add(key);
            result.add(key);
        }
        return result;
    }

    private int firstNonBlankLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (StringUtils.hasText(lines[i])) {
                return i;
            }
        }
        return -1;
    }

    /** 仅提取表头中明确给出的日期，不把序号、文件名或其他数字猜成日期。 */
    public LocalDate extractSourceDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String[] lines = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int headerLine = firstNonBlankLine(lines);
        if (headerLine < 0) {
            return null;
        }
        LocalDate sourceDate = null;
        for (String header : splitLine(lines[headerLine], chooseDelimiter(lines[headerLine]))) {
            Matcher matcher = SOURCE_DATE_HEADER.matcher(header.replaceAll("[\\s\\p{Z}]+", ""));
            if (!matcher.matches()) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
            } catch (DateTimeException error) {
                throw new IllegalArgumentException("源表头日期无效：" + header.trim());
            }
            if (sourceDate != null && !sourceDate.equals(date)) {
                throw new IllegalArgumentException("源表头包含不一致的日期：" + sourceDate + "、" + date);
            }
            sourceDate = date;
        }
        return sourceDate;
    }

    private char chooseDelimiter(String header) {
        long tabs = header.chars().filter(c -> c == '\t').count();
        long commas = header.chars().filter(c -> c == ',').count();
        return tabs > 0 || tabs >= commas ? '\t' : ',';
    }

    private List<String> splitLine(String line, char delimiter) {
        if (delimiter == '\t') {
            return Arrays.asList(line.split("\\t", -1));
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == delimiter && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private int findColumn(String canonical, Map<String, String> mapping, List<String> headers,
                           List<String> normalizedHeaders, List<String> aliases) {
        if (mapping != null && StringUtils.hasText(mapping.get(canonical))) {
            String expected = normalizeHeader(mapping.get(canonical));
            for (int i = 0; i < normalizedHeaders.size(); i++) {
                if (normalizedHeaders.get(i).equals(expected)) {
                    return i;
                }
            }
        }
        for (String alias : aliases) {
            String expected = normalizeHeader(alias);
            for (int i = 0; i < normalizedHeaders.size(); i++) {
                if (normalizedHeaders.get(i).equals(expected)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int findPopularityColumn(Map<String, String> mapping, List<String> headers) {
        List<String> normalizedHeaders = headers.stream().map(this::normalizePopularityHeader).toList();
        String mapped = mapping == null ? null : mapping.get("metricValue");
        if (StringUtils.hasText(mapped)) {
            String expected = normalizePopularityHeader(mapped);
            if (!POPULARITY_HEADER.matcher(expected).matches()) {
                throw new IllegalArgumentException("排序指标必须使用“" + POPULARITY_METRIC + "”，不能映射为“" + mapped + "”");
            }
            int index = normalizedHeaders.indexOf(expected);
            requireColumn(index, mapped, headers);
            return index;
        }
        for (int i = 0; i < normalizedHeaders.size(); i++) {
            if (POPULARITY_HEADER.matcher(normalizedHeaders.get(i)).matches()) {
                return i;
            }
        }
        return -1;
    }

    private void requireColumn(int index, String name, List<String> headers) {
        if (index < 0) {
            throw new IllegalArgumentException("缺少必需列“" + name + "”，当前列为：" + String.join("、", headers));
        }
    }

    private String normalizeHeader(String header) {
        return normalizePopularityHeader(header).replace("：", "").replace(":", "");
    }

    private String normalizePopularityHeader(String header) {
        return header == null ? "" : header.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", "");
    }

    private String cleanCode(String value) {
        String code = value == null ? "" : value.trim();
        if (code.startsWith("=")) {
            code = code.substring(1).trim();
        }
        while (code.startsWith("'") || code.startsWith("‘") || code.startsWith("’")) {
            code = code.substring(1).trim();
        }
        while (code.endsWith("'") || code.endsWith("‘") || code.endsWith("’")) {
            code = code.substring(0, code.length() - 1).trim();
        }
        return code;
    }

    private BigDecimal parseDecimal(String value, String label, int line) {
        try {
            String cleaned = value.trim().replace(",", "");
            return new BigDecimal(cleaned);
        } catch (Exception error) {
            throw new IllegalArgumentException("第" + line + "行“" + label + "”不是数字");
        }
    }

}
