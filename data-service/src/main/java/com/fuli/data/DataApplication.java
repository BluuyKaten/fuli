package com.fuli.data;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootApplication
@EnableDiscoveryClient
@MapperScan({"com.fuli.data.mapper", "com.fuli.trade.mapper"})
@EnableScheduling
public class DataApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataApplication.class, args);
    }

    /** 同步任务线程池（替代裸 Thread） */
    @Bean
    public TaskScheduler syncTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("sync-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
