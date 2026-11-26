package com.enterprise.sfgexporter.model;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative immutable YAML configuration record for a single Oracle-to-SFG export pipeline.
 *
 * <p>Adding a new pipeline requires only appending a block under {@code app.jobs[]}
 * (and an optional {@code .sql} file for {@link SourceType#SQL_QUERY} jobs)—no Java code changes.</p>
 */
public record ExportJobConfig(
        String name,
        @DefaultValue("true") boolean enabled,
        String cron,
        @DefaultValue("UTC") String timeZone,
        @DefaultValue("STORED_PROCEDURE") SourceType sourceType,
        String schemaName,
        String packageName,
        String procedureName,
        @DefaultValue("P_RESULT_CURSOR") String cursorParamName,
        String sqlFile,
        Map<String, Object> parameters,
        @DefaultValue("1000") int fetchSize,
        @DefaultValue("300") int queryTimeoutSeconds,
        String filePrefix,
        @DefaultValue("yyyyMMdd_HHmmss") String timestampPattern,
        @DefaultValue CsvOptions csv,
        String sftpTarget,
        @DefaultValue("") String remoteDir
) {

    public ExportJobConfig {
        parameters = parameters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        csv = csv != null ? csv : CsvOptions.defaults();
        remoteDir = remoteDir != null ? remoteDir : "";
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .name(name)
                .enabled(enabled)
                .cron(cron)
                .timeZone(timeZone)
                .sourceType(sourceType)
                .schemaName(schemaName)
                .packageName(packageName)
                .procedureName(procedureName)
                .cursorParamName(cursorParamName)
                .sqlFile(sqlFile)
                .parameters(parameters)
                .fetchSize(fetchSize)
                .queryTimeoutSeconds(queryTimeoutSeconds)
                .filePrefix(filePrefix)
                .timestampPattern(timestampPattern)
                .csv(csv)
                .sftpTarget(sftpTarget)
                .remoteDir(remoteDir);
    }

    public static final class Builder {
        private String name;
        private boolean enabled = true;
        private String cron;
        private String timeZone = "UTC";
        private SourceType sourceType = SourceType.STORED_PROCEDURE;
        private String schemaName;
        private String packageName;
        private String procedureName;
        private String cursorParamName = "P_RESULT_CURSOR";
        private String sqlFile;
        private Map<String, Object> parameters = new LinkedHashMap<>();
        private int fetchSize = 1000;
        private int queryTimeoutSeconds = 300;
        private String filePrefix;
        private String timestampPattern = "yyyyMMdd_HHmmss";
        private CsvOptions csv = CsvOptions.defaults();
        private String sftpTarget;
        private String remoteDir = "";

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder cron(String cron) {
            this.cron = cron;
            return this;
        }

        public Builder timeZone(String timeZone) {
            this.timeZone = timeZone;
            return this;
        }

        public Builder sourceType(SourceType sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder schemaName(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        public Builder packageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder procedureName(String procedureName) {
            this.procedureName = procedureName;
            return this;
        }

        public Builder cursorParamName(String cursorParamName) {
            this.cursorParamName = cursorParamName;
            return this;
        }

        public Builder sqlFile(String sqlFile) {
            this.sqlFile = sqlFile;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters != null ? new LinkedHashMap<>(parameters) : new LinkedHashMap<>();
            return this;
        }

        public Builder fetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
            return this;
        }

        public Builder queryTimeoutSeconds(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            return this;
        }

        public Builder filePrefix(String filePrefix) {
            this.filePrefix = filePrefix;
            return this;
        }

        public Builder timestampPattern(String timestampPattern) {
            this.timestampPattern = timestampPattern;
            return this;
        }

        public Builder csv(CsvOptions csv) {
            this.csv = csv;
            return this;
        }

        public Builder sftpTarget(String sftpTarget) {
            this.sftpTarget = sftpTarget;
            return this;
        }

        public Builder remoteDir(String remoteDir) {
            this.remoteDir = remoteDir;
            return this;
        }

        public ExportJobConfig build() {
            return new ExportJobConfig(
                    name,
                    enabled,
                    cron,
                    timeZone,
                    sourceType,
                    schemaName,
                    packageName,
                    procedureName,
                    cursorParamName,
                    sqlFile,
                    parameters,
                    fetchSize,
                    queryTimeoutSeconds,
                    filePrefix,
                    timestampPattern,
                    csv,
                    sftpTarget,
                    remoteDir
            );
        }
    }
}
