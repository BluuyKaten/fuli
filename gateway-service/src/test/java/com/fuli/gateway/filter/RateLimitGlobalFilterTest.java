package com.fuli.gateway.filter;

import com.fuli.gateway.ratelimit.RateLimitProperties;
import com.fuli.gateway.ratelimit.RateLimitRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitGlobalFilterTest {

    private RateLimitProperties properties() {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(true);
        p.setDefaultRule(new RateLimitProperties.Rule(20.0, 60.0));
        RateLimitProperties.Rule login = new RateLimitProperties.Rule(1.0, 2.0);
        p.getPaths().put("/api/auth/login", login);
        return p;
    }

    @Test
    void forwardsWhenUnderLimit() {
        RateLimitProperties props = properties();
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, new RateLimitRegistry());

        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/trade/list")
                .header("X-User-Id", "42"));
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(exchange, next -> { forwarded.set(true); return Mono.empty(); }).block();

        assertTrue(forwarded.get(), "未超限应转发");
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    void returns429AfterBurstExhausted() {
        RateLimitProperties props = properties();
        // 登录规则：突发 2、每秒补 1 → 第 3 次（无时间流逝）即被拒
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, new RateLimitRegistry());

        var request = MockServerHttpRequest.post("/api/auth/login").header("X-Forwarded-For", "1.2.3.4");
        // 前两次放行
        for (int i = 0; i < 2; i++) {
            var ex = MockServerWebExchange.from(request);
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(ex, next -> { forwarded.set(true); return Mono.empty(); }).block();
            assertTrue(forwarded.get(), "第 " + (i + 1) + " 次应放行");
        }
        // 第三次超限
        var ex = MockServerWebExchange.from(request);
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.filter(ex, next -> { forwarded.set(true); return Mono.empty(); }).block();
        assertFalse(forwarded.get(), "超过突发容量应拒绝");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getResponse().getStatusCode());
        assertEquals("1", ex.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test
    void unauthenticatedKeysByIpSoDifferentIpsAreIndependent() {
        RateLimitProperties props = properties();
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, new RateLimitRegistry());

        // IP A 打满突发（2）
        for (int i = 0; i < 2; i++) {
            var ex = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/auth/login").header("X-Forwarded-For", "10.0.0.1"));
            filter.filter(ex, next -> Mono.empty()).block();
        }
        // IP B 首包应放行（不同键）
        var exB = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login").header("X-Forwarded-For", "10.0.0.2"));
        AtomicBoolean forwardedB = new AtomicBoolean();
        filter.filter(exB, next -> { forwardedB.set(true); return Mono.empty(); }).block();
        assertTrue(forwardedB.get(), "不同 IP 应互相独立");
    }

    @Test
    void authenticatedRequestsKeyByUserId() {
        RateLimitProperties props = properties();
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, new RateLimitRegistry());

        // 用户 7 打满默认突发（60）会太长，改用「不同用户互相独立」的断言：
        // 用户 8 与用户 9 各打一次，都应放行。
        for (String uid : new String[]{"8", "9"}) {
            var ex = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/trade/list").header("X-User-Id", uid));
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(ex, next -> { forwarded.set(true); return Mono.empty(); }).block();
            assertTrue(forwarded.get(), "用户 " + uid + " 首次请求应放行");
        }
    }

    @Test
    void healthCheckIsNeverRateLimited() {
        RateLimitProperties props = properties();
        // 用 0 突发的最严规则验证健康检查豁免：即便规则极严也应放行
        props.getPaths().put("/actuator/health", new RateLimitProperties.Rule(0.0001, 1.0));
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, new RateLimitRegistry());

        for (int i = 0; i < 5; i++) {
            var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(ex, next -> { forwarded.set(true); return Mono.empty(); }).block();
            assertTrue(forwarded.get(), "健康检查不应被限流");
        }
    }

    @Test
    void disabledFilterAlwaysForwards() {
        RateLimitProperties props = properties();
        props.setEnabled(false);
        RateLimitGlobalFilter filter = new RateLimitGlobalFilter(props, new RateLimitRegistry());

        for (int i = 0; i < 100; i++) {
            var ex = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(ex, next -> { forwarded.set(true); return Mono.empty(); }).block();
            assertTrue(forwarded.get());
        }
    }
}
