package com.fuli.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "tushare")
public class TushareConfig {

    private String token = "";

    private String apiUrl = "http://api.tushare.pro";

    private int timeout = 30000;

    /**
     * 检查 Token 是否已配置
     */
    public boolean isTokenConfigured() {
        return token != null && !token.trim().isEmpty();
    }
}
