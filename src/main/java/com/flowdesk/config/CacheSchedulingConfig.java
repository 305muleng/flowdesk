package com.flowdesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
public class CacheSchedulingConfig {

    @Bean(name = "taskSummaryCacheScheduler", destroyMethod = "shutdown")
    public ScheduledThreadPoolExecutor taskSummaryCacheScheduler() {
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(1);

        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

        return scheduler;
    }
}