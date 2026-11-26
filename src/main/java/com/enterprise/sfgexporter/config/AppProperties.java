package com.enterprise.sfgexporter.config;

import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable root configuration properties record bound from the {@code app.*} namespace in {@code application.yml}.
 *
 * <p>Component Javadocs in this record and its nested records are compiled by
 * {@code spring-boot-configuration-processor} into {@code META-INF/spring-configuration-metadata.json}
 * so IDEs provide inline documentation and validation when editing YAML pipelines.</p>
 *
 * @param storage                   Local filesystem paths and retention cleanup schedule for staging and backup CSVs.
 * @param scheduler                 Dedicated worker thread pool sizing and graceful shutdown configuration.
 * @param sftpTargets               Named IBM SFG SFTP target definitions referenced by {@code app.jobs[].sftp-target}.
 * @param jobs                      Declarative list of independent Oracle-to-SFG export pipelines.
 * @param allowInsecureSftpDevMode  Explicit opt-in required before any SFTP target may set {@code strict-host-key-checking: false}.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue StorageConfig storage,
        @DefaultValue SchedulerConfig scheduler,
        Map<String, SftpTargetConfig> sftpTargets,
        List<ExportJobConfig> jobs,
        @DefaultValue("false") boolean allowInsecureSftpDevMode
) {

    public AppProperties {
        storage = storage != null ? storage : StorageConfig.defaults();
        scheduler = scheduler != null ? scheduler : SchedulerConfig.defaults();
        sftpTargets = sftpTargets == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(sftpTargets));
        jobs = jobs == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(jobs));
    }

    public static AppProperties defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .storage(storage)
                .scheduler(scheduler)
                .sftpTargets(sftpTargets)
                .jobs(jobs)
                .allowInsecureSftpDevMode(allowInsecureSftpDevMode);
    }

    public static final class Builder {
        private StorageConfig storage = StorageConfig.defaults();
        private SchedulerConfig scheduler = SchedulerConfig.defaults();
        private Map<String, SftpTargetConfig> sftpTargets = new LinkedHashMap<>();
        private List<ExportJobConfig> jobs = new ArrayList<>();
        private boolean allowInsecureSftpDevMode = false;

        public Builder storage(StorageConfig storage) {
            this.storage = storage;
            return this;
        }

        public Builder scheduler(SchedulerConfig scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder sftpTargets(Map<String, SftpTargetConfig> sftpTargets) {
            this.sftpTargets = sftpTargets != null ? new LinkedHashMap<>(sftpTargets) : new LinkedHashMap<>();
            return this;
        }

        public Builder jobs(List<ExportJobConfig> jobs) {
            this.jobs = jobs != null ? new ArrayList<>(jobs) : new ArrayList<>();
            return this;
        }

        public Builder allowInsecureSftpDevMode(boolean allowInsecureSftpDevMode) {
            this.allowInsecureSftpDevMode = allowInsecureSftpDevMode;
            return this;
        }

        public AppProperties build() {
            return new AppProperties(storage, scheduler, sftpTargets, jobs, allowInsecureSftpDevMode);
        }
    }

    /**
     * Local filesystem directories and automated retention cleanup settings.
     *
     * @param stagingDir             Directory where CSV files are streamed before SFTP upload.
     * @param backupDir              Root archive directory containing {@code yyyy-MM-dd/} folders of uploaded CSVs.
     * @param sqlDir                 Optional allowlisted base directory confining external {@code file:} SQL resources.
     * @param retentionDays          Number of days to retain local backup CSVs (and failed staged CSVs) before deletion.
     * @param stagingPartMaxAgeHours Age in hours after which crash-orphaned {@code .part} files in {@code staging/} are purged.
     * @param cleanupCron            Spring 6-field cron expression for the daily backup cleanup sweep.
     * @param cleanupTimeZone        IANA time zone ID used to evaluate {@code cleanupCron} and backup date folders.
     */
    public record StorageConfig(
            @DefaultValue("./data/staging") String stagingDir,
            @DefaultValue("./data/backup") String backupDir,
            @DefaultValue("") String sqlDir,
            @DefaultValue("15") int retentionDays,
            @DefaultValue("24") int stagingPartMaxAgeHours,
            @DefaultValue("0 0 2 * * *") String cleanupCron,
            @DefaultValue("UTC") String cleanupTimeZone
    ) {

        public StorageConfig {
            sqlDir = sqlDir != null ? sqlDir : "";
        }

        public static StorageConfig defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .stagingDir(stagingDir)
                    .backupDir(backupDir)
                    .sqlDir(sqlDir)
                    .retentionDays(retentionDays)
                    .stagingPartMaxAgeHours(stagingPartMaxAgeHours)
                    .cleanupCron(cleanupCron)
                    .cleanupTimeZone(cleanupTimeZone);
        }

        public static final class Builder {
            private String stagingDir = "./data/staging";
            private String backupDir = "./data/backup";
            private String sqlDir = "";
            private int retentionDays = 15;
            private int stagingPartMaxAgeHours = 24;
            private String cleanupCron = "0 0 2 * * *";
            private String cleanupTimeZone = "UTC";

            public Builder stagingDir(String stagingDir) {
                this.stagingDir = stagingDir;
                return this;
            }

            public Builder backupDir(String backupDir) {
                this.backupDir = backupDir;
                return this;
            }

            public Builder sqlDir(String sqlDir) {
                this.sqlDir = sqlDir;
                return this;
            }

            public Builder retentionDays(int retentionDays) {
                this.retentionDays = retentionDays;
                return this;
            }

            public Builder stagingPartMaxAgeHours(int stagingPartMaxAgeHours) {
                this.stagingPartMaxAgeHours = stagingPartMaxAgeHours;
                return this;
            }

            public Builder cleanupCron(String cleanupCron) {
                this.cleanupCron = cleanupCron;
                return this;
            }

            public Builder cleanupTimeZone(String cleanupTimeZone) {
                this.cleanupTimeZone = cleanupTimeZone;
                return this;
            }

            public StorageConfig build() {
                return new StorageConfig(
                        stagingDir,
                        backupDir,
                        sqlDir,
                        retentionDays,
                        stagingPartMaxAgeHours,
                        cleanupCron,
                        cleanupTimeZone
                );
            }
        }
    }

    /**
     * Thread pool configuration for Spring's {@code ThreadPoolTaskScheduler}.
     *
     * @param poolSize                Maximum number of export/cleanup jobs that may execute concurrently on this node.
     * @param threadNamePrefix        Thread name prefix used in logs and thread dumps for scheduled worker threads.
     * @param awaitTerminationSeconds Maximum seconds to wait for in-flight jobs to finish during graceful shutdown.
     */
    public record SchedulerConfig(
            @DefaultValue("4") int poolSize,
            @DefaultValue("sfg-job-") String threadNamePrefix,
            @DefaultValue("60") int awaitTerminationSeconds
    ) {

        public static SchedulerConfig defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .poolSize(poolSize)
                    .threadNamePrefix(threadNamePrefix)
                    .awaitTerminationSeconds(awaitTerminationSeconds);
        }

        public static final class Builder {
            private int poolSize = 4;
            private String threadNamePrefix = "sfg-job-";
            private int awaitTerminationSeconds = 60;

            public Builder poolSize(int poolSize) {
                this.poolSize = poolSize;
                return this;
            }

            public Builder threadNamePrefix(String threadNamePrefix) {
                this.threadNamePrefix = threadNamePrefix;
                return this;
            }

            public Builder awaitTerminationSeconds(int awaitTerminationSeconds) {
                this.awaitTerminationSeconds = awaitTerminationSeconds;
                return this;
            }

            public SchedulerConfig build() {
                return new SchedulerConfig(poolSize, threadNamePrefix, awaitTerminationSeconds);
            }
        }
    }
}
