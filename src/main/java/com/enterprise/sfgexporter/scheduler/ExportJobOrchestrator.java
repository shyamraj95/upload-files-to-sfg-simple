package com.enterprise.sfgexporter.scheduler;

import com.enterprise.sfgexporter.backup.BackupRetentionService;
import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.db.OracleDataExtractor;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.JobExecutionResult;
import com.enterprise.sfgexporter.sftp.AtomicSftpUploader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates the end-to-end execution of a single configured export pipeline:
 * <ol>
 *     <li>Acquires a non-blocking per-job {@link ReentrantLock} to prevent overlapping runs on this node.</li>
 *     <li>Binds {@code jobName} and an 8-character {@code runId} to SLF4J {@link MDC} for log correlation.</li>
 *     <li>Streams Oracle PL/SQL or SQL results into a timestamped CSV in {@code staging/}.</li>
 *     <li>Atomically uploads the staged CSV ({@code .tmp} -&gt; {@code .csv}) to IBM SFG via SFTP.</li>
 *     <li>Archives the uploaded CSV from {@code staging/} into {@code backup/yyyy-MM-dd/}.</li>
 *     <li>Records the immutable {@link JobExecutionResult} for {@link ExportJobsHealthIndicator}.</li>
 * </ol>
 *
 * <p>On SFTP failure after retries are exhausted, the staged CSV is retained in {@code staging/} and
 * its filename is logged so operators can inspect or re-deliver it without re-querying Oracle.</p>
 */
@Service
public class ExportJobOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ExportJobOrchestrator.class);

    private final OracleDataExtractor dataExtractor;
    private final AtomicSftpUploader sftpUploader;
    private final BackupRetentionService backupRetentionService;

    private final Map<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();
    private final Map<String, JobExecutionResult> lastResults = new ConcurrentHashMap<>();

    public ExportJobOrchestrator(
            OracleDataExtractor dataExtractor,
            AtomicSftpUploader sftpUploader,
            BackupRetentionService backupRetentionService
    ) {
        this.dataExtractor = dataExtractor;
        this.sftpUploader = sftpUploader;
        this.backupRetentionService = backupRetentionService;
    }

    public JobExecutionResult executeJob(ExportJobConfig job) {
        String jobName = job.getName();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Instant startedAt = Instant.now();

        ReentrantLock lock = jobLocks.computeIfAbsent(jobName, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.warn("Skipping scheduled run [runId={}] for job '{}': previous execution is still in progress",
                    runId, jobName);
            return JobExecutionResult.skippedConcurrent(jobName, runId, startedAt);
        }

        MDC.put("jobName", jobName);
        MDC.put("runId", runId);
        Path stagedFilePath = null;
        try {
            log.info("Starting export job '{}' [sourceType={}, sftpTarget={}]",
                    jobName, job.getSourceType(), job.getSftpTarget());

            // Step 1 & 2: Stream Oracle PL/SQL or SQL ResultSet directly to timestamped CSV in staging/
            StreamingCsvWriter.StagedCsvFile stagedCsv = dataExtractor.extractToCsv(job);
            stagedFilePath = stagedCsv.filePath();
            if (!stagedCsv.hasFile() || stagedFilePath == null) {
                Duration elapsed = Duration.between(startedAt, Instant.now());
                JobExecutionResult skippedEmpty = JobExecutionResult.skippedEmpty(jobName, runId, startedAt, elapsed);
                lastResults.put(jobName, skippedEmpty);
                return skippedEmpty;
            }

            // Step 3: Atomic SFTP upload (.tmp -> rename .csv) with exponential backoff retry
            String remotePath = sftpUploader.uploadAtomically(
                    stagedFilePath, job.getSftpTarget(), job.getRemoteDir());

            // Step 4: Move uploaded CSV from staging/ to backup/yyyy-MM-dd/
            Path backupPath = backupRetentionService.archiveToBackup(stagedFilePath);

            Duration elapsed = Duration.between(startedAt, Instant.now());
            JobExecutionResult result = JobExecutionResult.success(
                    jobName,
                    runId,
                    stagedCsv.rowCount(),
                    stagedCsv.fileName(),
                    remotePath,
                    backupPath.toString(),
                    startedAt,
                    elapsed
            );
            lastResults.put(jobName, result);

            log.info("Completed export job '{}' successfully: rows={}, file='{}', remote='{}', durationMs={}",
                    jobName, result.rowCount(), result.fileName(), result.remotePath(), elapsed.toMillis());
            return result;

        } catch (IOException | RuntimeException ex) {
            Duration elapsed = Duration.between(startedAt, Instant.now());
            String sanitizedError = sanitizeErrorMessage(ex);
            if (stagedFilePath != null) {
                log.error("Export job '{}' failed after {} ms (staged file retained in staging dir as '{}'): {}",
                        jobName, elapsed.toMillis(), stagedFilePath.getFileName(), sanitizedError, ex);
            } else {
                log.error("Export job '{}' failed after {} ms: {}", jobName, elapsed.toMillis(), sanitizedError, ex);
            }

            JobExecutionResult failed = JobExecutionResult.failed(jobName, runId, startedAt, elapsed, sanitizedError);
            lastResults.put(jobName, failed);
            return failed;
        } finally {
            MDC.remove("jobName");
            MDC.remove("runId");
            lock.unlock();
        }
    }

    private String sanitizeErrorMessage(Throwable ex) {
        String rawMessage = ex.getMessage();
        String cleaned = rawMessage == null ? "No error message" : rawMessage.replaceAll("[\\r\\n]+", " ");
        if (cleaned.length() > 300) {
            cleaned = cleaned.substring(0, 300) + "...";
        }
        return ex.getClass().getSimpleName() + ": " + cleaned;
    }

    public Map<String, JobExecutionResult> getLastResults() {
        return Collections.unmodifiableMap(lastResults);
    }
}
