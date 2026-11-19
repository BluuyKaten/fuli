package com.fuli.trade.quant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 大智慧原生导出结果适配器，首版读取固定目录中的最新文本导出文件。 */
@Component
public class DazhihuiExportCollector implements QuantCollector {
    private final Path exportDirectory;
    private final QuantRankingParser parser = new QuantRankingParser();

    public DazhihuiExportCollector(@Value("${quant-ranking.collector.export-dir:}") String exportDirectory) {
        this.exportDirectory = exportDirectory == null || exportDirectory.isBlank() ? null : Path.of(exportDirectory);
    }

    @Override
    public QuantRankingDtos.CollectResult collect() {
        if (exportDirectory == null) {
            return QuantRankingDtos.CollectResult.failure("未配置大智慧导出目录，请先完成原生导出或复制");
        }
        if (!Files.isDirectory(exportDirectory)) {
            return QuantRankingDtos.CollectResult.failure("大智慧导出目录不存在：" + exportDirectory);
        }
        try (Stream<Path> files = Files.list(exportDirectory)) {
            Path latest = files.filter(Files::isRegularFile)
                    .filter(path -> hasTextExtension(path.getFileName().toString()))
                    .max(Comparator.comparingLong(this::lastModified)).orElse(null);
            if (latest == null) {
                return QuantRankingDtos.CollectResult.failure("大智慧导出目录中没有 CSV、TSV 或 TXT 文件");
            }
            return QuantRankingDtos.CollectResult.success(latest.getFileName().toString(),
                    parser.decodeText(Files.readAllBytes(latest)));
        } catch (IOException error) {
            return QuantRankingDtos.CollectResult.failure("读取大智慧导出文件失败：" + error.getMessage());
        }
    }

    private boolean hasTextExtension(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt");
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

}
