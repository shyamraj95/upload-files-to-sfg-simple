package com.enterprise.sfgexporter.scheduler;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Dynamically registers each enabled job from {@code app.jobs[]} on Spring's
 * {@link ThreadPoolTaskScheduler} using its configured cron expression and IANA time zone.
 *
 * <p>Implements {@link SchedulingConfigurer} so adding or modifying a PL/SQL-to-SFG pipeline
 * requires only updating {@code application.yml} (and an optional {@code .sql} file)—zero Java changes.</p>
 */
@Component
public class DynamicJobScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DynamicJobScheduler.class);

    private final AppProperties properties;
    private final ExportJobOrchestrator orchestrator;
    private final ThreadPoolTaskScheduler exportTaskScheduler;

    public DynamicJobScheduler(
            AppProperties properties,
            ExportJobOrchestrator orchestrator,
            ThreadPoolTaskScheduler exportTaskScheduler
    ) {
        this.properties = properties;
        this.orchestrator = orchestrator;
        this.exportTaskScheduler = exportTaskScheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(exportTaskScheduler);

        int registeredCount = 0;
        for (ExportJobConfig job : properties.getJobs()) {
            if (!job.isEnabled()) {
                log.info("Export job '{}' is disabled in configuration; skipping registration", job.getName());
                continue;
            }

            CronTrigger trigger = new CronTrigger(job.getCron(), ZoneId.of(job.getTimeZone()));
            taskRegistrar.addTriggerTask(() -> orchestrator.executeJob(job), trigger);
            registeredCount++;

            log.info("Registered scheduled export job '{}' with cron='{}' (zone='{}', target='{}')",
                    job.getName(), job.getCron(), job.getTimeZone(), job.getSftpTarget());
        }

        if (registeredCount == 0) {
            log.warn("No enabled export jobs were found under app.jobs[]");
        } else {
            log.info("Successfully registered {} active export job(s)", registeredCount);
        }
    }
}
