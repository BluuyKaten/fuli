package com.fuli.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Data
@Configuration
@ConfigurationProperties(prefix = "tushare")
public class TushareConfig {

    private String token;

    private String apiUrl = "http://api.tushare.pro";

    private int timeout = 30000;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
