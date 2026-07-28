package com.fuli;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@MapperScan("com.fuli.trade.mapper")
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.fuli")
public class TradeServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeServiceApplication.class, args);
    }
}
