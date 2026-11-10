package com.fuli.data.mcp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.okx.OkxClient;
import com.fuli.data.okx.OkxTradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * P5：工具返回、异常隔离，以及「绝不暴露写工具」的硬约束。
 *
 * <p>ADR-0010 之后私有工具需要显式账户身份（MCP 没有请求上下文），
 * 因此这里补两条：未配置身份时给出可操作的错误、配置后按该身份调用。
 */
@ExtendWith(MockitoExtension.class)
class OkxMcpToolsTest {

    @Mock
    private OkxClient client;

    @Mock
    private OkxTradeService tradeService;

    @Mock
    private OkxConfig okxConfig;

    @InjectMocks
    private OkxMcpTools tools;

    @Test
    void readToolReturnsOkJson() {
        when(client.publicGet(eq("/api/v5/market/ticker"), any()))
                .thenReturn(JsonNodeFactory.instance.arrayNode());
        String result = tools.getTicker("BTC-USDT");
        assertTrue(result.contains("\"ok\":true"));
        assertTrue(result.contains("\"data\""));
    }

    @Test
    void toolFailureIsContainedInsteadOfBreakingTheSession() {
        when(client.publicGet(any(), any())).thenThrow(new IllegalStateException("boom"));
        String result = tools.getTicker("BTC-USDT");
        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("boom"));
    }

    @Test
    void privateToolWithoutOwnerIdentityExplainsInsteadOfGuessing() {
        lenient().when(okxConfig.getMcpOwnerUserId()).thenReturn(0L);
        String result = tools.getBalance("USDT");
        assertTrue(result.contains("\"ok\":false"), "未配置身份时不能假装成功");
        assertTrue(result.contains("okx.mcp-owner-user-id"), "错误里要指路到具体配置项");
    }

    @Test
    void privateToolUsesConfiguredOwnerIdentity() {
        when(okxConfig.getMcpOwnerUserId()).thenReturn(42L);
        when(tradeService.balance(eq(42L), any())).thenReturn(JsonNodeFactory.instance.arrayNode());

        String result = tools.getBalance("USDT");

        assertTrue(result.contains("\"ok\":true"));
        assertTrue(result.contains("\"data\""));
    }

    @Test
    void noWriteToolIsRegistered() {
        long writes = Arrays.stream(OkxMcpTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpTool.class))
                .map(Method::getName)
                .filter(name -> Arrays.stream(new String[]{"place", "cancel", "amend", "set", "stop", "create", "close"})
                        .anyMatch(name::contains))
                .count();
        assertTrue(writes == 0, "MCP 工具层不允许出现写操作：调用链不经过 OkxAuthz 写 scope");
    }
}
