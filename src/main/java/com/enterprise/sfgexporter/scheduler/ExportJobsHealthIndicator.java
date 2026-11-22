package com.enterprise.sfgexporter.scheduler;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.model.JobExecutionResult;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes lightweight operational health metadata for configured export jobs via Spring Boot Actuator
 * ({@code org.springframework.boot.health.contributor.HealthIndicator} in Spring Boot 4+).
 *
 * <p>Reports {@code UP} when no job's latest execution failed, and transitions to {@code DOWN} if any
 * job's most recent run ended in {@link JobExecutionResult.Status#FAILED}—automatically returning to
 * {@code UP} once that job succeeds on a subsequent run. Only safe operational metadata (counts,
 * filenames, timings) is exposed; database row payloads and secrets are never included.</p>
 */
@Component("exportJobs")
public class ExportJobsHealthIndicator implements HealthIndicator {

    private final AppProperties properties;
    private final ExportJobOrchestrator orchestrator;

    public ExportJobsHealthIndicator(AppProperties properties, ExportJobOrchestrator orchestrator) {
        this.properties = properties;
        this.orchestrator = orchestrator;
    }

    @Override
    public Health health() {
        long enabledJobs = properties.getJobs().stream()
                .filter(job -> job != null && job.isEnabled())
                .count();
        Map<String, JobExecutionResult> lastResults = orchestrator.getLastResults();

        boolean anyFailed = lastResults.values().stream()
                .anyMatch(r -> r != null && r.status() == JobExecutionResult.Status.FAILED);

        Health.Builder builder = anyFailed ? Health.down() : Health.up();
        builder.withDetail("configuredJobs", properties.getJobs().size())
                .withDetail("enabledJobs", enabledJobs);

        Map<String, Object> jobSummaries = new LinkedHashMap<>();
        for (Map.Entry<String, JobExecutionResult> entry : lastResults.entrySet()) {
            JobExecutionResult res = entry.getValue();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", res.status().name());
            summary.put("rowCount", res.rowCount());
            summary.put("startedAt", res.startedAt().toString());
            summary.put("durationMs", res.duration().toMillis());
            if (res.fileName() != null) {
                summary.put("fileName", res.fileName());
            }
            jobSummaries.put(entry.getKey(), summary);
        }
        builder.withDetail("lastRuns", jobSummaries);
        return builder.build();
    }
}
