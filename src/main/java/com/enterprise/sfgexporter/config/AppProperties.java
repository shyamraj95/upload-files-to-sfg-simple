package com.enterprise.sfgexporter.config;

import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root configuration properties bound from the {@code app.*} namespace in {@code application.yml}.
 *
 * <p>Field-level Javadocs in this class and its nested types are compiled by
 * {@code spring-boot-configuration-processor} into {@code META-INF/spring-configuration-metadata.json}
 * so IDEs provide inline documentation and validation when editing YAML pipelines.</p>
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Local filesystem paths and retention cleanup schedule for staging and backup CSVs. */
    private StorageConfig storage = new StorageConfig();

    /** Dedicated worker thread pool sizing and graceful shutdown configuration. */
    private SchedulerConfig scheduler = new SchedulerConfig();

    /** Named IBM SFG SFTP target definitions referenced by {@code app.jobs[].sftp-target}. */
    private Map<String, SftpTargetConfig> sftpTargets = new LinkedHashMap<>();

    /** Declarative list of independent Oracle-to-SFG export pipelines. */
    private List<ExportJobConfig> jobs = new ArrayList<>();

    /**
     * Explicit opt-in required before any SFTP target may set {@code strict-host-key-checking: false}.
     * Must remain {@code false} in production to prevent accidental MITM exposure.
     */
    private boolean allowInsecureSftpDevMode = false;

    public StorageConfig getStorage() {
        return storage;
    }

    public void setStorage(StorageConfig storage) {
        this.storage = storage != null ? storage : new StorageConfig();
    }

    public SchedulerConfig getScheduler() {
        return scheduler;
    }

    public void setScheduler(SchedulerConfig scheduler) {
        this.scheduler = scheduler != null ? scheduler : new SchedulerConfig();
    }

    public Map<String, SftpTargetConfig> getSftpTargets() {
        return sftpTargets;
    }

    public void setSftpTargets(Map<String, SftpTargetConfig> sftpTargets) {
        this.sftpTargets = sftpTargets != null ? sftpTargets : new LinkedHashMap<>();
    }

    public List<ExportJobConfig> getJobs() {
        return jobs;
    }

    public void setJobs(List<ExportJobConfig> jobs) {
        this.jobs = jobs != null ? jobs : new ArrayList<>();
    }

    public boolean isAllowInsecureSftpDevMode() {
        return allowInsecureSftpDevMode;
    }

    public void setAllowInsecureSftpDevMode(boolean allowInsecureSftpDevMode) {
        this.allowInsecureSftpDevMode = allowInsecureSftpDevMode;
    }

    /**
     * Local filesystem directories and automated retention cleanup settings.
     */
    public static class StorageConfig {
        /** Directory where CSV files are streamed before SFTP upload. */
        private String stagingDir = "./data/staging";

        /** Root archive directory containing {@code yyyy-MM-dd/} folders of uploaded CSVs. */
        private String backupDir = "./data/backup";

        /**
         * Optional allowlisted base directory confining external {@code file:} SQL resources.
         * When set, all {@code file:} SQL paths must resolve strictly inside this directory.
         */
        private String sqlDir = "";

        /** Number of days to retain local backup CSVs (and failed staged CSVs) before automated deletion (default: 15). */
        private int retentionDays = 15;

        /** Age in hours after which crash-orphaned {@code .part} files in {@code staging/} are purged (default: 24). */
        private int stagingPartMaxAgeHours = 24;

        /** Spring 6-field cron expression for the daily backup cleanup sweep. */
        private String cleanupCron = "0 0 2 * * *";

        /** IANA time zone ID used to evaluate {@code cleanupCron} and backup date folders. */
        private String cleanupTimeZone = "UTC";

        public String getStagingDir() {
            return stagingDir;
        }

        public void setStagingDir(String stagingDir) {
            this.stagingDir = stagingDir;
        }

        public String getBackupDir() {
            return backupDir;
        }

        public void setBackupDir(String backupDir) {
            this.backupDir = backupDir;
        }

        public String getSqlDir() {
            return sqlDir;
        }

        public void setSqlDir(String sqlDir) {
            this.sqlDir = sqlDir;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public int getStagingPartMaxAgeHours() {
            return stagingPartMaxAgeHours;
        }

        public void setStagingPartMaxAgeHours(int stagingPartMaxAgeHours) {
            this.stagingPartMaxAgeHours = stagingPartMaxAgeHours;
        }

        public String getCleanupCron() {
            return cleanupCron;
        }

        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }

        public String getCleanupTimeZone() {
            return cleanupTimeZone;
        }

        public void setCleanupTimeZone(String cleanupTimeZone) {
            this.cleanupTimeZone = cleanupTimeZone;
        }
    }

    /**
     * Thread pool configuration for Spring's {@code ThreadPoolTaskScheduler}.
     */
    public static class SchedulerConfig {
        /** Maximum number of export/cleanup jobs that may execute concurrently on this node. */
        private int poolSize = 4;

        /** Thread name prefix used in logs and thread dumps for scheduled worker threads. */
        private String threadNamePrefix = "sfg-job-";

        /** Maximum seconds to wait for in-flight jobs to finish during graceful shutdown. */
        private int awaitTerminationSeconds = 60;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getAwaitTerminationSeconds() {
            return awaitTerminationSeconds;
        }

        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
            this.awaitTerminationSeconds = awaitTerminationSeconds;
        }
    }
}
