package com.fuli.trade.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Feign 请求拦截器：自动添加内部调用密钥
 */
@Component
public class InternalKeyFeignInterceptor implements RequestInterceptor {

    @Value("${fuli.internal-key:your-internal-key-here-change-me}")
    private String internalKey;

    @Override
    public void apply(RequestTemplate template) {
        // 只对内部接口添加密钥
        if (template.path() != null && template.path().contains("/internal/")) {
            template.header("X-Internal-Key", internalKey);
        }
    }
}
