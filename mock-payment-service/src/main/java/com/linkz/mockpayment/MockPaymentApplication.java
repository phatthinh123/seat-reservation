package com.tpthinh.mockpayment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;

@SpringBootApplication
@EnableAsync
public class MockPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPaymentApplication.class, args);
    }

    @Bean(name = "webhookExecutor")
    public TaskExecutor webhookExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(5);
        ex.setMaxPoolSize(10);
        ex.setQueueCapacity(100);
        ex.setRejectedExecutionHandler(new CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
