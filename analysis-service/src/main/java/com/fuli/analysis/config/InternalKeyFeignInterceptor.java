package com.fuli.analysis.config;

import com.fuli.common.api.config.InternalKeyProperties;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.stereotype.Component;

/**
 * Feign 请求拦截器：自动添加内部调用密钥。
 *
 * <p>密钥统一由 {@link InternalKeyProperties} 注入，与 auth-service 过滤器共用同一密钥。
 */
@Component
public class InternalKeyFeignInterceptor implements RequestInterceptor {

    private final InternalKeyProperties internalKeyProperties;

    /** @param internalKeyProperties 统一内部服务密钥配置（来自 common-api） */
    public InternalKeyFeignInterceptor(InternalKeyProperties internalKeyProperties) {
        this.internalKeyProperties = internalKeyProperties;
    }

    @Override
    public void apply(RequestTemplate template) {
        // 只对内部接口添加密钥
        if (template.path() != null && template.path().contains("/internal/")) {
            template.header("X-Internal-Key", internalKeyProperties.getInternalKey());
        }
    }
}
