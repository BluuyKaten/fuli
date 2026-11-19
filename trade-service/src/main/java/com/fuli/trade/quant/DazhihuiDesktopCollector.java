package com.fuli.trade.quant;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Java 调用本机助手执行大智慧原生复制命令，只读取本次新生成的表格文件。 */
@Component
@Primary
public class DazhihuiDesktopCollector implements QuantCollector {
    private final boolean windows;
    private final CaptureRunner runner;

    public DazhihuiDesktopCollector() {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                DazhihuiDesktopCollector::runProcess);
    }

    DazhihuiDesktopCollector(boolean windows, CaptureRunner runner) {
        this.windows = windows;
        this.runner = runner;
    }

    @Override
    public QuantRankingDtos.CollectResult collect() { return collect(""); }

    @Override
    public QuantRankingDtos.CollectResult collect(String executablePath) {
        if (!windows) return QuantRankingDtos.CollectResult.failure("大智慧自动采集需要在本机 Windows 桌面运行 Java 服务");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("fuli-dzh-");
            Path script = directory.resolve("capture.ps1");
            try (var resource = getClass().getResourceAsStream("/quant/capture-dazhihui.ps1")) {
                if (resource == null) throw new IOException("未找到内置大智慧采集助手");
                Files.copy(resource, script);
            }
            Path output = directory.resolve("table.tsv");
            runner.capture(script, executablePath == null ? "" : executablePath, output);
            if (!Files.isRegularFile(output) || Files.size(output) == 0) throw new IOException("本次采集未生成表格文件");
            if (Files.size(output) > 20L * 1024 * 1024) throw new IOException("本次表格超过 20MB，请检查大智慧页面");
            return QuantRankingDtos.CollectResult.success("大智慧常用菜单复制", Files.readString(output, StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return QuantRankingDtos.CollectResult.failure("大智慧采集被中断");
        } catch (Exception error) {
            return QuantRankingDtos.CollectResult.failure(error.getMessage() == null ? "大智慧采集失败" : error.getMessage());
        } finally {
            if (directory != null) {
                try (var files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
                } catch (IOException ignored) { /* 临时文件由系统后续清理，不影响已返回的采集结果。 */ }
            }
        }
    }

    private static void runProcess(Path script, String executablePath, Path output) throws Exception {
        Path log = script.resolveSibling("capture.log");
        var arguments = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-ExecutionPolicy", "Bypass",
                "-File", script.toString(), "-OutputPath", output.toString(), "-TimeoutSeconds", "20"));
        if (!executablePath.isBlank()) arguments.addAll(List.of("-ExecutablePath", executablePath));
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            throw new IOException("大智慧采集超时，请确认程序已登录并打开含人气{wyz}的行情表");
        }
        if (process.exitValue() != 0) {
            String error = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8).trim() : "";
            if (error.length() > 800) error = error.substring(0, 800);
            throw new IOException(error.isEmpty() ? "大智慧本机采集失败" : error);
        }
    }

    @FunctionalInterface
    interface CaptureRunner { void capture(Path script, String executablePath, Path output) throws Exception; }
}
