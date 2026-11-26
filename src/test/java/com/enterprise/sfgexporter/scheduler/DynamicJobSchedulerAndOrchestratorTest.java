package com.enterprise.sfgexporter.scheduler;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.db.OracleDataExtractor;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.JobExecutionResult;
import com.enterprise.sfgexporter.sftp.AtomicSftpUploader;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicJobSchedulerAndOrchestratorTest {

    @Test
    void registersOnlyEnabledJobsOnTaskScheduler() {
        ExportJobConfig enabledJob = new ExportJobConfig();
        enabledJob.setName("job_one");
        enabledJob.setEnabled(true);
        enabledJob.setCron("0 0 6 * * *");
        enabledJob.setTimeZone("UTC");
        enabledJob.setSftpTarget("sfg-primary");

        ExportJobConfig disabledJob = new ExportJobConfig();
        disabledJob.setName("job_two");
        disabledJob.setEnabled(false);
        disabledJob.setCron("0 30 6 * * *");
        disabledJob.setTimeZone("UTC");
        disabledJob.setSftpTarget("sfg-primary");

        AppProperties props = new AppProperties();
        props.setJobs(List.of(enabledJob, disabledJob));

        ExportJobOrchestrator orchestrator = mock(ExportJobOrchestrator.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();

        try {
            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            DynamicJobScheduler dynamicScheduler = new DynamicJobScheduler(props, orchestrator, scheduler);
            dynamicScheduler.configureTasks(registrar);

            assertThat(registrar.getTriggerTaskList()).hasSize(1);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void orchestratesExtractUploadAndArchiveEndToEnd() throws Exception {
        OracleDataExtractor extractor = mock(OracleDataExtractor.class);
        AtomicSftpUploader uploader = mock(AtomicSftpUploader.class);
        BackupRetentionService backup = mock(BackupRetentionService.class);

        ExportJobConfig job = new ExportJobConfig();
        job.setName("daily_gl");
        job.setSftpTarget("sfg-primary");
        job.setRemoteDir("/finance");

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
}
