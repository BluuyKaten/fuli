package com.fuli.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitPropertiesTest {

    private RateLimitProperties properties() {
        RateLimitProperties p = new RateLimitProperties();
        p.setDefaultRule(new RateLimitProperties.Rule(20.0, 60.0));
        p.getPaths().put("/api/auth/login", new RateLimitProperties.Rule(1.0, 10.0));
        p.getPaths().put("/api/auth/register", new RateLimitProperties.Rule(0.5, 5.0));
        p.getPaths().put("/api/okx", new RateLimitProperties.Rule(2.0, 20.0));
        return p;
    }

    @Test
    void exactMatchWins() {
        RateLimitProperties.Rule rule = properties().ruleFor("/api/auth/login");
        assertEquals(1.0, rule.getReplenishRate());
        assertEquals(10.0, rule.getBurstCapacity());
    }

    @Test
    void longestPrefixWins() {
        RateLimitProperties.Rule rule = properties().ruleFor("/api/okx/trade/order");
        assertEquals(2.0, rule.getReplenishRate());
    }

    @Test
    void fallsBackToDefault() {
        RateLimitProperties.Rule rule = properties().ruleFor("/api/trade/list");
        assertEquals(20.0, rule.getReplenishRate());
        assertEquals(60.0, rule.getBurstCapacity());
    }

    @Test
    void nullAndEmptyPathUseDefault() {
        RateLimitProperties p = properties();
        assertEquals(20.0, p.ruleFor(null).getReplenishRate());
        assertEquals(20.0, p.ruleFor("").getReplenishRate());
    }
}
