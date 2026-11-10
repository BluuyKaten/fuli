package com.fuli.gateway.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关限流配置，绑定 {@code fuli.rate-limit.*}。
 *
 * <p>两级限流：
 * <ul>
 *   <li>{@code default.*}：默认规则，作用于已认证请求（键为 userId），较宽松；</li>
 *   <li>{@code paths.*}：按路径前缀覆盖，作用于登录/注册等未认证入口（键为客户端 IP），
 *       用于防爆破，较严格。</li>
 * </ul>
 *
 * <pre>
 * fuli:
 *   rate-limit:
 *     enabled: true
 *     default:
 *       replenish-rate: 20   # 令牌/秒
 *       burst-capacity: 60   # 突发容量
 *     paths:
 *       /api/auth/login:     { replenish-rate: 1,  burst-capacity: 10 }
 *       /api/auth/register:  { replenish-rate: 0.5, burst-capacity: 5 }
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "fuli.rate-limit")
public class RateLimitProperties {

    /** 总开关；关闭后过滤器直接放行。 */
    private boolean enabled = true;

    /** 默认规则：每个已认证用户。 */
    private Rule defaultRule = new Rule(20.0, 60.0);

    /** 按路径前缀的覆盖规则；匹配最长前缀，未命中用 defaultRule。 */
    private Map<String, Rule> paths = new LinkedHashMap<>();

    /**
     * 选一条作用于给定路径的规则：先精确匹配，再最长前缀匹配，最后回落 defaultRule。
     */
    public Rule ruleFor(String path) {
        if (path == null || paths.isEmpty()) {
            return defaultRule;
        }
        Rule exact = paths.get(path);
        if (exact != null) {
            return exact;
        }
        Rule best = null;
        int bestLen = -1;
        for (Map.Entry<String, Rule> e : paths.entrySet()) {
            String prefix = e.getKey();
            if (path.startsWith(prefix) && prefix.length() > bestLen) {
                best = e.getValue();
                bestLen = prefix.length();
            }
        }
        return best != null ? best : defaultRule;
    }

    /** 单条限流规则。 */
    @Data
    public static class Rule {

        /** 令牌补充速率（令牌/秒）。 */
        private double replenishRate;

        /** 突发容量（桶上限）。 */
        private double burstCapacity;

        public Rule() {
        }

        public Rule(double replenishRate, double burstCapacity) {
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
        }
    }
}
