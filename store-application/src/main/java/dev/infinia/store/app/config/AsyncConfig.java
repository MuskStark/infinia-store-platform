package dev.infinia.store.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Executor for the async scan pipeline (design §8.2). */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "scanExecutor")
    public ThreadPoolTaskExecutor scanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("scan-");
        executor.initialize();
        return executor;
    }
}
