package dev.infinia.store.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Executor for the async scan pipeline (design §8.2). */
@Configuration
@EnableAsync
@EnableScheduling // the scan watchdog reconcile loop (audit P2-6)
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

    /**
     * Upstream aggregation runs. Kept separate from scanExecutor: a sync waits
     * on scan-pipeline progress, so sharing one pool could queue the scans a
     * sync is waiting for behind the sync itself.
     */
    @Bean(name = "upstreamSyncExecutor")
    public ThreadPoolTaskExecutor upstreamSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("upstream-sync-");
        executor.initialize();
        return executor;
    }
}
