package com.enterprise.sfgexporter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/**
 * Configures the dedicated {@link ThreadPoolTaskScheduler} shared by all dynamically registered
 * export jobs ({@code DynamicJobScheduler}) and the daily retention cleanup task
 * ({@code BackupRetentionService}).
 *
 * <p>Graceful shutdown behavior: in-flight jobs are allowed to finish up to
 * {@code app.scheduler.await-termination-seconds}, while future delayed/periodic cron triggers
 * are cancelled immediately so shutdown does not hang waiting for the next scheduled tick.</p>
 */
@Configuration
public class SchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfiguration.class);

    @Bean
    public ThreadPoolTaskScheduler exportTaskScheduler(AppProperties properties) {
        AppProperties.SchedulerConfig cfg = properties.scheduler();

        // Do NOT call scheduler.initialize() manually here: Spring calls InitializingBean.afterPropertiesSet()
        // once on @Bean creation. Overriding createExecutor() ensures shutdown policies are applied to the
        // exact ScheduledThreadPoolExecutor managed by Spring without creating a duplicate thread pool.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler() {
            private static final long serialVersionUID = 1L;

            @Override
            protected ScheduledExecutorService createExecutor(
                    int poolSize,
                    ThreadFactory threadFactory,
                    RejectedExecutionHandler rejectedExecutionHandler
            ) {
                ScheduledExecutorService executor = super.createExecutor(poolSize, threadFactory, rejectedExecutionHandler);
                if (executor instanceof ScheduledThreadPoolExecutor stpe) {
                    // Cancel queued future cron ticks on shutdown while still letting active runs complete
                    stpe.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                    stpe.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                }
                return executor;
            }
        };

        scheduler.setPoolSize(cfg.poolSize());
        scheduler.setThreadNamePrefix(cfg.threadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(cfg.awaitTerminationSeconds());
        // Immediately purge cancelled tasks from the work queue to avoid retaining references
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(ex ->
                log.error("Unhandled exception in scheduled task thread: {}", ex.getMessage(), ex));

        log.info("Configured ThreadPoolTaskScheduler with poolSize={} and prefix='{}'",
                cfg.poolSize(), cfg.threadNamePrefix());
        return scheduler;
    }
}
