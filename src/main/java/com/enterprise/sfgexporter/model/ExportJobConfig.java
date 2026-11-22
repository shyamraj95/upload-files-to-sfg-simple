package com.enterprise.sfgexporter.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Declarative YAML configuration for a single Oracle-to-SFG export pipeline.
 *
 * <p>Adding a new pipeline requires only appending a block under {@code app.jobs[]}
 * (and an optional {@code .sql} file for {@link SourceType#SQL_QUERY} jobs)—no Java code changes.</p>
 */
public class ExportJobConfig {

    /** Unique job identifier (must match {@code ^[a-zA-Z0-9_]+$}); used in logs, locks, and health status. */
    private String name;

    /** Controls whether this job is registered with the scheduler on startup. */
    private boolean enabled = true;

    /** Spring 6-field cron expression ({@code second minute hour day-of-month month day-of-week}). */
    private String cron;

    /** IANA time zone ID used for cron evaluation and timestamped output filenames (default: {@code UTC}). */
    private String timeZone = "UTC";

    /** Data extraction strategy: Oracle PL/SQL {@code SYS_REFCURSOR} or external parameterized SQL file. */
    private SourceType sourceType = SourceType.STORED_PROCEDURE;

    /** Optional Oracle schema name for {@code STORED_PROCEDURE} jobs (allowlist {@code ^[a-zA-Z0-9_]+$}). */
    private String schemaName;

    /** Optional Oracle PL/SQL package name for {@code STORED_PROCEDURE} jobs (allowlist {@code ^[a-zA-Z0-9_]+$}). */
    private String packageName;

    /** Oracle stored procedure name (required when {@code sourceType == STORED_PROCEDURE}). */
    private String procedureName;

    /** Name of the {@code OUT SYS_REFCURSOR} parameter on the Oracle stored procedure. */
    private String cursorParamName = "P_RESULT_CURSOR";

    /** Spring resource URI to an external {@code .sql} file (required when {@code sourceType == SQL_QUERY}). */
    private String sqlFile;

    /**
     * Optional IN parameters bound to the PL/SQL procedure (named {@code param => ?}) or SQL query
     * ({@code :param}). LinkedHashMap preserves deterministic declaration order from YAML.
     */
    private Map<String, Object> parameters = new LinkedHashMap<>();

    /** JDBC cursor fetch size controlling how many rows {@code ojdbc11} fetches per network round-trip. */
    private int fetchSize = 1000;

    /** Maximum JDBC statement execution timeout in seconds before Oracle cancels the query. */
    private int queryTimeoutSeconds = 300;

    /** Filename prefix for generated CSV files ({@code <filePrefix>_<timestamp>.csv}; allowlist {@code ^[a-zA-Z0-9_]+$}). */
    private String filePrefix;

    /** {@link java.time.format.DateTimeFormatter} pattern appended after {@code filePrefix}. */
    private String timestampPattern = "yyyyMMdd_HHmmss";

    /** Per-job CSV formatting options (delimiter, headers, quoting, charset, empty-file behavior). */
    private CsvOptions csv = new CsvOptions();

    /** Key matching an entry under {@code app.sftp-targets} defining the target IBM SFG server. */
    private String sftpTarget;

    /** Target mailbox/subdirectory appended to the SFTP target's {@code remote-base-dir}. */
    private String remoteDir = "";

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public String getCursorParamName() {
        return cursorParamName;
    }

    public void setCursorParamName(String cursorParamName) {
        this.cursorParamName = cursorParamName;
    }

    public String getSqlFile() {
        return sqlFile;
    }

    public void setSqlFile(String sqlFile) {
        this.sqlFile = sqlFile;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters != null ? parameters : new LinkedHashMap<>();
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public String getFilePrefix() {
        return filePrefix;
    }

    public void setFilePrefix(String filePrefix) {
        this.filePrefix = filePrefix;
    }

    public String getTimestampPattern() {
        return timestampPattern;
    }

    public void setTimestampPattern(String timestampPattern) {
        this.timestampPattern = timestampPattern;
    }

    public CsvOptions getCsv() {
        return csv;
    }

    public void setCsv(CsvOptions csv) {
        this.csv = csv != null ? csv : new CsvOptions();
    }

    public String getSftpTarget() {
        return sftpTarget;
    }

    public void setSftpTarget(String sftpTarget) {
        this.sftpTarget = sftpTarget;
    }

    public String getRemoteDir() {
        return remoteDir;
    }

    public void setRemoteDir(String remoteDir) {
        this.remoteDir = remoteDir;
    }
}
