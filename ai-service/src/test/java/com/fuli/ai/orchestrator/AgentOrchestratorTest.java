package com.fuli.ai.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P9：规则编排的意图识别（未接 LLM，plan 是纯函数可独立验证）。 */
class AgentOrchestratorTest {

    @Test
    void routesCryptoQuestionsToCryptoSymbols() {
        assertEquals("BTC-USDT.CRYPTO", AgentOrchestrator.plan("BTC 最近怎么样").symbol());
        assertEquals("ETH-USDT.CRYPTO", AgentOrchestrator.plan("看看 eth 走势").symbol());
        assertEquals("SOL-USDT.CRYPTO", AgentOrchestrator.plan("SOL 呢").symbol());
        assertEquals("BTC-USDT.CRYPTO", AgentOrchestrator.plan("加密行情").symbol());
    }

    @Test
    void routesAshareQuestionsToAshareSymbol() {
        assertEquals("600519.SH", AgentOrchestrator.plan("茅台最近走势").symbol());
        assertEquals("600519.SH", AgentOrchestrator.plan("600519").symbol());
    }

    @Test
    void everyPlanUsesThePublicBarsContract() {
        assertEquals("market/bars", AgentOrchestrator.plan("随便问问").tool());
        assertEquals("market/bars", AgentOrchestrator.plan(null).tool());
    }
}
