package com.fuli.trade.quant;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DazhihuiDesktopCollectorTest {
    @Test
    void shouldReadHelperOutputWithoutTreatingExecutablePathAsShellCode() {
        var received = new AtomicReference<String>();
        var collector = new DazhihuiDesktopCollector(true, (script, executable, output) -> {
            received.set(executable);
            Files.writeString(output, "2026/11/16\t代码\t名称\t人气(wyz)\n1\t'600000'\t浦发银行\t16.582\n", StandardCharsets.UTF_8);
        });
        var result = collector.collect("F:\\大智慧 (64)\\dzh2.exe");
        assertTrue(result.success(), result.failureReason());
        assertEquals("F:\\大智慧 (64)\\dzh2.exe", received.get());
        assertTrue(result.text().contains("人气(wyz)"));
    }

    @Test
    void shouldReturnFailureWhenNoNewCaptureFileWasProduced() {
        var collector = new DazhihuiDesktopCollector(true, (script, executable, output) -> {});
        var result = collector.collect();
        assertFalse(result.success());
        assertTrue(result.failureReason().contains("未生成"));
    }

    @Test
    void shouldNotLaunchDesktopCaptureOnUnsupportedSystem() {
        var collector = new DazhihuiDesktopCollector(false, (script, executable, output) -> {
            fail("非 Windows 环境不应启动采集进程");
        });
        assertFalse(collector.collect().success());
    }
}
