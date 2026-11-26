package com.enterprise.sfgexporter.scheduler;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.db.OracleDataExtractor;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.JobExecutionResult;
import com.enterprise.sfgexporter.sftp.AtomicSftpUploader;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicJobSchedulerAndOrchestratorTest {

    /**
     * Verifies that {@link DynamicJobScheduler} registers cron triggers only for jobs
     * with {@code enabled=true} and skips disabled jobs (including when zero jobs are enabled).
     */
    @Test
    void registersOnlyEnabledJobsOnTaskScheduler() {
        ExportJobConfig enabledJob = ExportJobConfig.builder()
                .name("job_one")
                .enabled(true)
                .cron("0 0 6 * * *")
                .timeZone("UTC")
                .sftpTarget("sfg-primary")
                .build();

        ExportJobConfig disabledJob = ExportJobConfig.builder()
                .name("job_two")
                .enabled(false)
                .cron("0 30 6 * * *")
                .timeZone("UTC")
                .sftpTarget("sfg-primary")
                .build();

        AppProperties props = AppProperties.builder()
                .jobs(List.of(enabledJob, disabledJob))
                .build();

        ExportJobOrchestrator orchestrator = mock(ExportJobOrchestrator.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();

        try {
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            DynamicJobScheduler dynamicScheduler = new DynamicJobScheduler(props, orchestrator, scheduler);
            dynamicScheduler.configureTasks(registrar);

            assertThat(registrar.getTriggerTaskList()).hasSize(1);

            // Also verify zero triggers are registered when all jobs are disabled
            ScheduledTaskRegistrar emptyRegistrar = new ScheduledTaskRegistrar();
            DynamicJobScheduler disabledOnlyScheduler = new DynamicJobScheduler(
                    AppProperties.builder().jobs(List.of(disabledJob)).build(), orchestrator, scheduler);
            disabledOnlyScheduler.configureTasks(emptyRegistrar);
            assertThat(emptyRegistrar.getTriggerTaskList()).isEmpty();
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * Verifies the happy-path orchestration pipeline: Oracle extraction to a staged CSV,
     * atomic SFTP upload to IBM SFG, local backup archiving, and recording a {@code SUCCESS} result.
     */
    @Test
    void orchestratesExtractUploadAndArchiveEndToEnd() throws Exception {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig job = ExportJobConfig.builder()
                .name("daily_gl")
                .sftpTarget("sfg-primary")
                .remoteDir("/finance")
                .build();

        Path stagedPath = Path.of("/tmp/staging/GL_20261122_060000.csv");
        Path backupPath = Path.of("/tmp/backup/2026-11-22/GL_20261122_060000.csv");

        when(extractor.extractToCsv(job))
                .thenReturn(new StreamingCsvWriter.StagedCsvFile(stagedPath, "GL_20261122_060000.csv", 42L));
        when(uploader.uploadAtomically(stagedPath, "sfg-primary", "/finance"))
                .thenReturn("/inbound/finance/GL_20261122_060000.csv");
        when(backup.archiveToBackup(stagedPath)).thenReturn(backupPath);

        ExportJobOrchestrator orchestrator = new ExportJobOrchestrator(extractor, uploader, backup);
        JobExecutionResult result = orchestrator.executeJob(job);

        assertThat(result.status()).isEqualTo(JobExecutionResult.Status.SUCCESS);
        assertThat(result.rowCount()).isEqualTo(42L);
        assertThat(result.remotePath()).isEqualTo("/inbound/finance/GL_20261122_060000.csv");
        assertThat(result.backupPath()).isEqualTo(backupPath.toString());

        verify(extractor).extractToCsv(job);
        verify(uploader).uploadAtomically(stagedPath, "sfg-primary", "/finance");
        verify(backup).archiveToBackup(stagedPath);
    }

    /**
     * Verifies that when SFTP upload succeeds but local backup archiving fails, the job is marked
     * {@code FAILED} without re-uploading a duplicate file to IBM SFG.
     */
    @Test
    void returnsFailedAndDoesNotRetryUploadWhenArchiveFailsAfterUpload() throws Exception {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig job = ExportJobConfig.builder()
                .name("archive_fail_job")
                .sftpTarget("sfg-primary")
                .remoteDir("/finance")
                .build();

        Path stagedPath = Path.of("/tmp/staging/GL_ARCHIVE_FAIL.csv");
        when(extractor.extractToCsv(job))
                .thenReturn(new StreamingCsvWriter.StagedCsvFile(stagedPath, "GL_ARCHIVE_FAIL.csv", 10L));
        when(uploader.uploadAtomically(stagedPath, "sfg-primary", "/finance"))
                .thenReturn("/inbound/finance/GL_ARCHIVE_FAIL.csv");
        when(backup.archiveToBackup(stagedPath)).thenThrow(new IOException("Backup disk full"));

        ExportJobOrchestrator orchestrator = new ExportJobOrchestrator(extractor, uploader, backup);
        JobExecutionResult result = orchestrator.executeJob(job);

        assertThat(result.status()).isEqualTo(JobExecutionResult.Status.FAILED);
        assertThat(result.errorMessage()).contains("Backup disk full");
        verify(uploader, times(1)).uploadAtomically(stagedPath, "sfg-primary", "/finance");
        verify(backup, times(1)).archiveToBackup(stagedPath);
    }

    /**
     * Verifies that when extraction produces 0 rows with {@code write-empty-file=false}
     * (no staged file generated), SFTP upload and backup archiving are skipped and
     * {@code SKIPPED_EMPTY} is recorded.
     */
    @Test
    void skipsUploadAndBackupWhenExtractorProducesNoFile() throws Exception {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig job = ExportJobConfig.builder()
                .name("zero_row_job")
                .sftpTarget("sfg-primary")
                .remoteDir("/finance")
                .build();

        when(extractor.extractToCsv(job)).thenReturn(new StreamingCsvWriter.StagedCsvFile(null, null, 0L));

        ExportJobOrchestrator orchestrator = new ExportJobOrchestrator(extractor, uploader, backup);
        JobExecutionResult result = orchestrator.executeJob(job);

        assertThat(result.status()).isEqualTo(JobExecutionResult.Status.SKIPPED_EMPTY);
        assertThat(result.rowCount()).isZero();
        verify(uploader, never()).uploadAtomically(any(), any(), any());
        verify(backup, never()).archiveToBackup(any());
    }

    /**
     * Verifies the per-job non-blocking {@link java.util.concurrent.locks.ReentrantLock} guard:
     * if a job trigger fires while the previous execution of the same job is still running,
     * the overlapping trigger immediately returns {@code SKIPPED_CONCURRENT} without querying Oracle.
     */
    @Test
    void skipsOverlappingConcurrentExecutionForSameJob() throws Exception {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig job = ExportJobConfig.builder()
                .name("long_running_job")
                .sftpTarget("sfg-primary")
                .remoteDir("/finance")
                .build();

        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch allowExtractionFinish = new CountDownLatch(1);
        when(extractor.extractToCsv(job)).thenAnswer(inv -> {
            extractionStarted.countDown();
            if (!allowExtractionFinish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
            return new StreamingCsvWriter.StagedCsvFile(null, null, 0L);
        });

        ExportJobOrchestrator orchestrator = new ExportJobOrchestrator(extractor, uploader, backup);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JobExecutionResult> firstRun = executor.submit(() -> orchestrator.executeJob(job));
            assertThat(extractionStarted.await(2, TimeUnit.SECONDS)).isTrue();

            JobExecutionResult overlappingRun = orchestrator.executeJob(job);
            assertThat(overlappingRun.status()).isEqualTo(JobExecutionResult.Status.SKIPPED_CONCURRENT);

            allowExtractionFinish.countDown();
            assertThat(firstRun.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(JobExecutionResult.Status.SKIPPED_EMPTY);
        }
        verify(extractor, times(1)).extractToCsv(job);
    }

    /**
     * Verifies that SFTP upload failures retain the staged CSV in {@code staging/} without moving it
     * to {@code backup/}, that Oracle {@link SQLException} messages are redacted to vendorCode/sqlState
     * (preventing PII/SQL leakage), and that SLF4J {@link MDC} keys are always cleared after execution.
     */
    @Test
    void retainsStagedCsvOnUploadFailureAndSanitizesSqlErrorsAndClearsMdc() throws Exception {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig uploadFailJob = ExportJobConfig.builder()
                .name("sftp_fail_job")
                .sftpTarget("sfg-primary")
                .remoteDir("/finance")
                .build();
        Path stagedPath = Path.of("/tmp/staging/SFTP_FAIL.csv");
        when(extractor.extractToCsv(uploadFailJob))
                .thenReturn(new StreamingCsvWriter.StagedCsvFile(stagedPath, "SFTP_FAIL.csv", 5L));
        when(uploader.uploadAtomically(stagedPath, "sfg-primary", "/finance"))
                .thenThrow(new AtomicSftpUploader.SftpUploadException("Connection refused", null));

        ExportJobOrchestrator orchestrator = new ExportJobOrchestrator(extractor, uploader, backup);
        JobExecutionResult sftpFailed = orchestrator.executeJob(uploadFailJob);

        assertThat(sftpFailed.status()).isEqualTo(JobExecutionResult.Status.FAILED);
        verify(backup, never()).archiveToBackup(any());
        assertThat(MDC.get("jobName")).isNull();
        assertThat(MDC.get("runId")).isNull();

        ExportJobConfig sqlFailJob = ExportJobConfig.builder()
                .name("sql_fail_job")
                .sftpTarget("sfg-primary")
                .build();
        SQLException sensitiveSqlEx = new SQLException("ORA-00942: sensitive row SSN=123-45-6789", "42000", 942);
        when(extractor.extractToCsv(sqlFailJob))
                .thenThrow(new DataRetrievalFailureException("DB error", sensitiveSqlEx));

        JobExecutionResult sqlFailed = orchestrator.executeJob(sqlFailJob);
        assertThat(sqlFailed.status()).isEqualTo(JobExecutionResult.Status.FAILED);
        assertThat(sqlFailed.errorMessage())
                .isEqualTo("SQLException[vendorCode=942, sqlState=42000]")
                .doesNotContain("123-45-6789");
    }

    /**
     * Verifies {@link ExportJobOrchestrator#getLastResults()} tracks each job's latest execution
     * outcome, recording {@code FAILED} on error and updating to {@code SKIPPED_EMPTY} once recovered.
     */
    @Test
    void tracksLatestJobExecutionResultAcrossFailureAndRecovery() {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig job = ExportJobConfig.builder()
                .name("tracked_job")
                .enabled(true)
                .sftpTarget("sfg-primary")
                .build();

        ExportJobOrchestrator orchestrator = new ExportJobOrchestrator(extractor, uploader, backup);
        assertThat(orchestrator.getLastResults()).isEmpty();

        // Simulate failure on run #1 -> FAILED, then recovery on run #2 -> SKIPPED_EMPTY
        when(extractor.extractToCsv(job))
                .thenThrow(new IllegalStateException("Oracle unreachable"))
                .thenReturn(new StreamingCsvWriter.StagedCsvFile(null, null, 0L));

        orchestrator.executeJob(job);
        assertThat(orchestrator.getLastResults())
                .containsKey("tracked_job")
                .extractingByKey("tracked_job")
                .extracting(JobExecutionResult::status)
                .isEqualTo(JobExecutionResult.Status.FAILED);

        orchestrator.executeJob(job);
        assertThat(orchestrator.getLastResults())
                .containsKey("tracked_job")
                .extractingByKey("tracked_job")
                .extracting(JobExecutionResult::status)
                .isEqualTo(JobExecutionResult.Status.SKIPPED_EMPTY);
    }
}
