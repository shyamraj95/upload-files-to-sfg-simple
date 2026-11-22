package com.enterprise.sfgexporter.config;

import com.enterprise.sfgexporter.model.CsvOptions;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.RetryConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import com.enterprise.sfgexporter.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-fast startup security and configuration validator, plus reusable allowlist/path guards.
 *
 * <p>Runs automatically during Spring context startup ({@link InitializingBean}) to reject unsafe
 * or incomplete YAML configurations before any scheduler trigger fires:</p>
 * <ul>
 *     <li>Enforces strict allowlist regex {@code ^[a-zA-Z0-9_]+$} on SQL identifiers and file prefixes
 *         to prevent SQL injection and path traversal.</li>
 *     <li>Validates cron expressions, time zones, timestamp filename patterns, and CSV options.</li>
 *     <li>Verifies that enabled {@code SQL_QUERY} resources exist and enabled SFTP targets define
 *         authentication, strong cryptographic algorithms, and {@code known_hosts} when strict host
 *         key checking is enabled.</li>
 * </ul>
 */
@Component
public class SecurityValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecurityValidator.class);

    /**
     * Strict allowlist for SQL identifiers (schema, package, procedure, cursor, parameter names)
     * and local file prefixes/job names. Blocks whitespace, quotes, semicolons, dots, and slashes.
     * Without {@code Pattern.UNICODE_CHARACTER_CLASS}, {@code \w} matches ASCII {@code [a-zA-Z0-9_]} only.
     */
    public static final Pattern STRICT_IDENTIFIER_PATTERN = Pattern.compile("^\\w+$");

    /** Allowed characters in a formatted timestamp segment embedded in local/remote CSV filenames. */
    private static final Pattern SAFE_TIMESTAMP_SAMPLE_PATTERN = Pattern.compile("^[\\w-]+$");

    /** Allowed temporary suffix for atomic SFTP uploads (must start with a dot, e.g. {@code .tmp}). */
    private static final Pattern SAFE_TEMP_SUFFIX_PATTERN = Pattern.compile("^\\.[\\w-]+$");

    /** Allowed POSIX characters for remote SFG paths (blocks control chars, spaces, backslashes). */
    private static final Pattern SAFE_REMOTE_PATH_PATTERN = Pattern.compile("^[\\w./-]*$");

    /** Allowed Spring resource URIs for external SQL files (must end in {@code .sql}). */
    private static final Pattern SAFE_SQL_RESOURCE_PATTERN = Pattern.compile("^(classpath:|file:)[\\w./-]+\\.sql$");

    private final AppProperties properties;
    private final ResourceLoader resourceLoader;

    @Autowired
    public SecurityValidator(AppProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    SecurityValidator(AppProperties properties) {
        this(properties, new DefaultResourceLoader());
    }

    @Override
    public void afterPropertiesSet() {
        validateAll(properties);
        log.info("Configuration and security validation passed for {} configured job(s) and {} SFTP target(s)",
                properties.getJobs().size(), properties.getSftpTargets().size());
    }

    public void validateAll(AppProperties props) {
        validateStorage(props.getStorage());
        validateScheduler(props.getScheduler());

        Set<String> seenJobNames = new HashSet<>();
        Set<String> referencedTargets = new HashSet<>();

        for (ExportJobConfig job : props.getJobs()) {
            validateJob(job, props.getSftpTargets(), seenJobNames);
            if (job.isEnabled()) {
                referencedTargets.add(job.getSftpTarget());
            }
        }

        // Validate every SFTP target that is actively referenced by at least one enabled job
        for (String targetKey : referencedTargets) {
            validateSftpTarget(targetKey, props.getSftpTargets().get(targetKey));
        }
    }

    private void validateStorage(AppProperties.StorageConfig storage) {
        requireNonBlank(storage.getStagingDir(), "app.storage.staging-dir");
        requireNonBlank(storage.getBackupDir(), "app.storage.backup-dir");
        if (storage.getRetentionDays() < 1) {
            throw new IllegalArgumentException("app.storage.retention-days must be >= 1");
        }
        validateCron(storage.getCleanupCron(), "app.storage.cleanup-cron");
        validateTimeZone(storage.getCleanupTimeZone(), "app.storage.cleanup-time-zone");
    }

    private void validateScheduler(AppProperties.SchedulerConfig scheduler) {
        if (scheduler.getPoolSize() < 1) {
            throw new IllegalArgumentException("app.scheduler.pool-size must be >= 1");
        }
        if (scheduler.getAwaitTerminationSeconds() < 0) {
            throw new IllegalArgumentException("app.scheduler.await-termination-seconds must be >= 0");
        }
    }

    public void validateJob(
            ExportJobConfig job,
            Map<String, SftpTargetConfig> sftpTargets,
            Set<String> seenJobNames
    ) {
        validateIdentifier(job.getName(), "job.name");
        if (!seenJobNames.add(job.getName())) {
            throw new IllegalArgumentException("Duplicate job name configured: " + job.getName());
        }

        String prefix = "app.jobs[" + job.getName() + "]";
        validateIdentifier(job.getFilePrefix(), prefix + ".file-prefix");
        validateCron(job.getCron(), prefix + ".cron");
        validateTimeZone(job.getTimeZone(), prefix + ".time-zone");
        validateTimestampPattern(job.getTimestampPattern(), prefix + ".timestamp-pattern");

        requireMin(job.getFetchSize(), 1, prefix + ".fetch-size");
        requireMin(job.getQueryTimeoutSeconds(), 1, prefix + ".query-timeout-seconds");

        validateJobSource(job, prefix);

        for (String paramName : job.getParameters().keySet()) {
            validateIdentifier(paramName, prefix + ".parameters key");
        }

        validateCsvOptions(job.getCsv(), prefix + ".csv");

        requireNonBlank(job.getSftpTarget(), prefix + ".sftp-target");
        if (!sftpTargets.containsKey(job.getSftpTarget())) {
            throw new IllegalArgumentException(
                    prefix + ".sftp-target references unknown target '" + job.getSftpTarget() + "'");
        }

        if (isNotBlank(job.getRemoteDir())) {
            validateRemotePath(job.getRemoteDir(), prefix + ".remote-dir");
        }
    }

    private void validateJobSource(ExportJobConfig job, String prefix) {
        if (job.getSourceType() == null) {
            throw new IllegalArgumentException(prefix + ".source-type must be STORED_PROCEDURE or SQL_QUERY");
        }

        if (job.getSourceType() == SourceType.STORED_PROCEDURE) {
            if (isNotBlank(job.getSchemaName())) {
                validateIdentifier(job.getSchemaName(), prefix + ".schema-name");
            }
            if (isNotBlank(job.getPackageName())) {
                validateIdentifier(job.getPackageName(), prefix + ".package-name");
            }
            validateIdentifier(job.getProcedureName(), prefix + ".procedure-name");
            validateIdentifier(job.getCursorParamName(), prefix + ".cursor-param-name");
        } else if (job.getSourceType() == SourceType.SQL_QUERY) {
            validateSqlFileLocation(job.getSqlFile(), prefix + ".sql-file");
            if (job.isEnabled() && !resourceLoader.getResource(job.getSqlFile()).exists()) {
                throw new IllegalArgumentException(
                        prefix + ".sql-file resource does not exist: " + job.getSqlFile());
            }
        }
    }

    public void validateSftpTarget(String targetKey, SftpTargetConfig target) {
        String prefix = "app.sftp-targets." + targetKey;
        if (target == null) {
            throw new IllegalArgumentException(prefix + " must not be null");
        }
        requireNonBlank(target.getHost(), prefix + ".host");
        if (target.getPort() < 1 || target.getPort() > 65535) {
            throw new IllegalArgumentException(prefix + ".port must be between 1 and 65535");
        }
        requireNonBlank(target.getUsername(), prefix + ".username");

        validateSftpSecuritySettings(targetKey, target, prefix);

        if (!isNotBlank(target.getTempSuffix()) || !SAFE_TEMP_SUFFIX_PATTERN.matcher(target.getTempSuffix()).matches()) {
            throw new IllegalArgumentException(
                    prefix + ".temp-suffix must match " + SAFE_TEMP_SUFFIX_PATTERN.pattern());
        }

        if (isNotBlank(target.getRemoteBaseDir())) {
            validateRemotePath(target.getRemoteBaseDir(), prefix + ".remote-base-dir");
        }

        if (target.getConnectTimeoutMs() < 1000 || target.getAuthTimeoutMs() < 1000 || target.getChannelOpenTimeoutMs() < 1000) {
            throw new IllegalArgumentException(prefix + " timeouts must be >= 1000 ms");
        }

        validateRetryConfig(target.getRetry(), prefix + ".retry");
    }

    private void validateSftpSecuritySettings(String targetKey, SftpTargetConfig target, String prefix) {
        if (!target.hasPrivateKeyAuth() && !target.hasPasswordAuth()) {
            throw new IllegalArgumentException(
                    prefix + " must configure either private-key-path (preferred) or password via environment variables");
        }

        if (target.isStrictHostKeyChecking()) {
            requireNonBlank(target.getKnownHostsPath(),
                    prefix + ".known-hosts-path (required when strict-host-key-checking=true)");
        } else {
            log.warn("SECURITY WARNING: strict-host-key-checking is disabled for SFTP target '{}'. "
                    + "Do NOT disable host key verification in production!", targetKey);
        }

        requireNonEmptyList(target.getAllowedCiphers(), prefix + ".allowed-ciphers");
        requireNonEmptyList(target.getAllowedKeyExchanges(), prefix + ".allowed-key-exchanges");
        requireNonEmptyList(target.getAllowedMacs(), prefix + ".allowed-macs");
    }

    private void validateRetryConfig(RetryConfig retry, String prefix) {
        if (retry == null) {
            throw new IllegalArgumentException(prefix + " must not be null");
        }
        if (retry.getMaxAttempts() < 1) {
            throw new IllegalArgumentException(prefix + ".max-attempts must be >= 1");
        }
        if (retry.getInitialBackoffMs() < 0) {
            throw new IllegalArgumentException(prefix + ".initial-backoff-ms must be >= 0");
        }
        if (retry.getMultiplier() < 1.0) {
            throw new IllegalArgumentException(prefix + ".multiplier must be >= 1.0");
        }
        if (retry.getMaxBackoffMs() < retry.getInitialBackoffMs()) {
            throw new IllegalArgumentException(prefix + ".max-backoff-ms must be >= initial-backoff-ms");
        }
    }

    private void validateCsvOptions(CsvOptions csv, String prefix) {
        if (csv == null) {
            throw new IllegalArgumentException(prefix + " must not be null");
        }
        if (csv.getDelimiter() == null || csv.getDelimiter().length() != 1) {
            throw new IllegalArgumentException(prefix + ".delimiter must be exactly 1 character");
        }
        if (csv.getQuoteChar() != null && csv.getQuoteChar().length() > 1) {
            throw new IllegalArgumentException(prefix + ".quote-char must be empty or 1 character");
        }
        if (!Charset.isSupported(csv.getCharset())) {
            throw new IllegalArgumentException(prefix + ".charset is not supported: " + csv.getCharset());
        }
    }

    private void validateTimestampPattern(String pattern, String fieldName) {
        requireNonBlank(pattern, fieldName);
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            String sample = formatter.format(ZonedDateTime.now(ZoneId.of("UTC")));
            if (!SAFE_TIMESTAMP_SAMPLE_PATTERN.matcher(sample).matches()) {
                throw new IllegalArgumentException(
                        fieldName + " produces unsafe filename characters: '" + sample + "'");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + ex.getMessage(), ex);
        }
    }

    private void validateCron(String cron, String fieldName) {
        requireNonBlank(cron, fieldName);
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("Invalid Spring cron expression for " + fieldName + ": " + cron);
        }
    }

    private void validateTimeZone(String timeZone, String fieldName) {
        requireNonBlank(timeZone, fieldName);
        ZoneId.of(timeZone);
    }

    public static void validateIdentifier(String value, String fieldName) {
        if (value == null || !STRICT_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + ": must strictly match " + STRICT_IDENTIFIER_PATTERN.pattern());
        }
    }

    public static void validateSqlFileLocation(String sqlFile, String fieldName) {
        requireNonBlank(sqlFile, fieldName);
        if (sqlFile.contains("..") || sqlFile.contains("\\") || !SAFE_SQL_RESOURCE_PATTERN.matcher(sqlFile).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe or invalid " + fieldName + ": must be a safe classpath: or file: .sql resource without path traversal");
        }
    }

    public static void validateRemotePath(String remotePath, String fieldName) {
        if (remotePath == null) {
            return;
        }
        if (remotePath.contains("..")
                || remotePath.contains("\\")
                || !SAFE_REMOTE_PATH_PATTERN.matcher(remotePath).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe " + fieldName + ": path traversal and control characters are prohibited");
        }
    }

    /**
     * Defense-in-depth check ensuring a resolved local path stays strictly inside its base directory.
     */
    public static Path ensureChildPath(Path baseDir, Path candidate) {
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!normalizedCandidate.startsWith(normalizedBase)) {
            throw new SecurityException(
                    "Path traversal attempt detected: resolved path escapes base directory");
        }
        return normalizedCandidate;
    }

    private static void requireMin(int value, int min, String fieldName) {
        if (value < min) {
            throw new IllegalArgumentException(fieldName + " must be >= " + min);
        }
    }

    private static void requireNonEmptyList(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (!isNotBlank(value)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
