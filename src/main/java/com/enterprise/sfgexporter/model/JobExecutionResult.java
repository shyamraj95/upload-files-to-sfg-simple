package com.enterprise.sfgexporter.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable summary of a single export job execution.
 *
 * <p>Stores strictly operational metadata (counts, filenames, paths, timings, and sanitized error
 * summaries)—never database row contents or credentials—so it can safely be logged or surfaced via
 * Spring Boot Actuator health details.</p>
 *
 * @param jobName      configured job name from {@code app.jobs[].name}
 * @param runId        short 8-character correlation ID also bound to SLF4J MDC
 * @param status       terminal outcome of this execution
 * @param rowCount     total data rows written (excluding header)
 * @param fileName     generated timestamped CSV filename, or {@code null} if skipped/failed before write
 * @param remotePath   final remote SFG path after atomic rename, or {@code null} if not uploaded
 * @param backupPath   local archived path under {@code backup/yyyy-MM-dd/}, or {@code null} if not archived
 * @param startedAt    UTC timestamp when execution began
 * @param duration     total wall-clock elapsed duration
 * @param errorMessage sanitized, single-line error description when {@code status == FAILED}
 */
public record JobExecutionResult(
        String jobName,
        String runId,
        Status status,
        long rowCount,
        String fileName,
        String remotePath,
        String backupPath,
        Instant startedAt,
        Duration duration,
        String errorMessage
) {

    /** Terminal outcome of a scheduled job invocation. */
    public enum Status {
        /** Data extracted, CSV uploaded atomically to SFG, and local file archived to backup. */
        SUCCESS,
        /** Extract returned 0 rows and {@code csv.write-empty-file=false}, so upload was skipped. */
        SKIPPED_EMPTY,
        /** Previous run of the same job was still holding its execution lock on this instance. */
        SKIPPED_CONCURRENT,
        /** Unrecoverable error occurred during extraction, upload, or archiving. */
        FAILED
    }

    public static JobExecutionResult success(
            String jobName,
            String runId,
            long rowCount,
            String fileName,
            String remotePath,
            String backupPath,
            Instant startedAt,
            Duration duration
    ) {
        return new JobExecutionResult(
                jobName, runId, Status.SUCCESS, rowCount, fileName, remotePath, backupPath, startedAt, duration, null
        );
    }

    public static JobExecutionResult skippedEmpty(
            String jobName,
            String runId,
            Instant startedAt,
            Duration duration
    ) {
        return new JobExecutionResult(
                jobName, runId, Status.SKIPPED_EMPTY, 0L, null, null, null, startedAt, duration, null
        );
    }

    public static JobExecutionResult skippedConcurrent(String jobName, String runId, Instant startedAt) {
        return new JobExecutionResult(
                jobName, runId, Status.SKIPPED_CONCURRENT, 0L, null, null, null, startedAt, Duration.ZERO, null
        );
    }

    public static JobExecutionResult failed(
            String jobName,
            String runId,
            Instant startedAt,
            Duration duration,
            String sanitizedError
    ) {
        return new JobExecutionResult(
                jobName, runId, Status.FAILED, 0L, null, null, null, startedAt, duration, sanitizedError
        );
    }
}
