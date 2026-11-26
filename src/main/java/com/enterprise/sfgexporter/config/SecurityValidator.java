package com.enterprise.sfgexporter.config;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.enterprise.sfgexporter.model.CsvOptions;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.RetryConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;

/**
 * Fail-fast startup security and configuration validator, plus reusable
 * allowlist/path guards.
 *
 * <p>
 * Runs automatically during Spring context startup ({@link InitializingBean})
 * to reject unsafe
 * or incomplete YAML configurations before any scheduler trigger fires:
 * </p>
 * <ul>
 * <li>Enforces strict allowlist regex {@code ^[a-zA-Z0-9_]+$} on SQL
 * identifiers and file prefixes
 * to prevent SQL injection and path traversal.</li>
 * <li>Validates cron expressions, time zones, timestamp filename patterns, and
 * CSV options.</li>
 * <li>Verifies that enabled {@code SQL_QUERY} resources exist and enabled SFTP
 * targets define
 * authentication, strong cryptographic algorithms, and {@code known_hosts} when
 * strict host
 * key checking is enabled.</li>
 * </ul>
 */
@Component
public class SecurityValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecurityValidator.class);

    /**
     * Strict allowlist for SQL identifiers (schema, package, procedure, cursor,
     * parameter names)
     * and local file prefixes/job names. Blocks whitespace, quotes, semicolons,
     * dots, and slashes.
     * Without {@code Pattern.UNICODE_CHARACTER_CLASS}, {@code \w} matches ASCII
     * {@code [a-zA-Z0-9_]} only.
     */
    public static final Pattern STRICT_IDENTIFIER_PATTERN = Pattern.compile("^\\w+$");

    /**
     * Allowed characters in a formatted timestamp segment embedded in local/remote
     * CSV filenames.
     */
    private static final Pattern SAFE_TIMESTAMP_SAMPLE_PATTERN = Pattern.compile("^[\\w-]+$");

    /**
     * Allowed temporary suffix for atomic SFTP uploads (must start with a dot, e.g.
     * {@code .tmp}).
     */
    private static final Pattern SAFE_TEMP_SUFFIX_PATTERN = Pattern.compile("^\\.[\\w-]+$");

    /**
     * Allowed POSIX characters for remote SFG paths (blocks control chars, spaces,
     * backslashes).
     */
    private static final Pattern SAFE_REMOTE_PATH_PATTERN = Pattern.compile("^[\\w./-]*$");

    /**
     * Allowed Spring resource URIs for external SQL files (must end in
     * {@code .sql}).
     */
    private static final Pattern SAFE_SQL_RESOURCE_PATTERN = Pattern.compile("^(classpath:|file:)[\\w./-]+\\.sql$");

    /**
     * Allowed SFTP target hostnames / IPv4 / IPv6 addresses (blocks whitespace,
     * control chars, slashes, '@').
     */
    private static final Pattern SAFE_HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9._:-]+$");

    /**
     * Allowed SFTP login usernames (printable non-whitespace ASCII only; blocks
     * CRLF/control chars).
     */
    private static final Pattern SAFE_USERNAME_PATTERN = Pattern.compile("^[\\x21-\\x7E]+$");

    /** Allowed SFTP target key identifiers under {@code app.sftp-targets}. */
    private static final Pattern SAFE_TARGET_KEY_PATTERN = Pattern.compile("^[\\w.-]+$");

    /**
     * Allowed single-segment CSV filenames (blocks path separators and traversal).
     */
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[\\w.-]+$");

    private static final int MAX_RETRY_ATTEMPTS = 20;
    private static final long MAX_RETRY_BACKOFF_MS = 300_000L;

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
                properties.jobs().size(), properties.sftpTargets().size());
    }

    public void validateAll(AppProperties props) {
        validateStorage(props.storage());
        validateScheduler(props.scheduler());

        Set<String> seenJobNames = new HashSet<>();
        Set<String> seenFilePrefixes = new HashSet<>();
        Set<String> referencedTargets = new HashSet<>();
        String allowedSqlDir = props.storage().sqlDir();

        for (ExportJobConfig job : props.jobs()) {
            validateJob(job, props.sftpTargets(), seenJobNames, seenFilePrefixes, allowedSqlDir);
            if (job.enabled()) {
                referencedTargets.add(job.sftpTarget());
            }
        }

        // Validate every SFTP target that is actively referenced by at least one
        // enabled job
        for (String targetKey : referencedTargets) {
            validateSftpTarget(targetKey, props.sftpTargets().get(targetKey), props.allowInsecureSftpDevMode());
        }
    }

    private void validateStorage(AppProperties.StorageConfig storage) {
        requireNonBlank(storage.stagingDir(), "app.storage.staging-dir");
        requireNonBlank(storage.backupDir(), "app.storage.backup-dir");
        Path staging = Path.of(storage.stagingDir()).toAbsolutePath().normalize();
        Path backup = Path.of(storage.backupDir()).toAbsolutePath().normalize();
        if (staging.equals(backup) || staging.startsWith(backup) || backup.startsWith(staging)) {
            throw new IllegalArgumentException(
                    "app.storage.staging-dir and app.storage.backup-dir must be distinct, non-nested directories");
        }
        if (isNotBlank(storage.sqlDir())) {
            Path sqlDir = Path.of(storage.sqlDir()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(sqlDir) || !Files.isDirectory(sqlDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "app.storage.sql-dir must be an existing non-symlink directory: " + sqlDir);
            }
        }
        requireMin(storage.retentionDays(), 1, "app.storage.retention-days");
        requireMin(storage.stagingPartMaxAgeHours(), 1, "app.storage.staging-part-max-age-hours");
        validateCron(storage.cleanupCron(), "app.storage.cleanup-cron");
        validateTimeZone(storage.cleanupTimeZone(), "app.storage.cleanup-time-zone");
    }

    private void validateScheduler(AppProperties.SchedulerConfig scheduler) {
        if (scheduler.poolSize() < 1) {
            throw new IllegalArgumentException("app.scheduler.pool-size must be >= 1");
        }
        if (scheduler.awaitTerminationSeconds() < 0) {
            throw new IllegalArgumentException("app.scheduler.await-termination-seconds must be >= 0");
        }
    }

    public void validateJob(
            ExportJobConfig job,
            Map<String, SftpTargetConfig> sftpTargets,
            Set<String> seenJobNames,
            Set<String> seenFilePrefixes,
            String allowedSqlDir) {
        validateIdentifier(job.name(), "job.name");
        if (!seenJobNames.add(job.name())) {
            throw new IllegalArgumentException("Duplicate job name configured: " + job.name());
        }

        String prefix = "app.jobs[" + job.name() + "]";
        validateIdentifier(job.filePrefix(), prefix + ".file-prefix");
        if (!seenFilePrefixes.add(job.filePrefix())) {
            throw new IllegalArgumentException(
                    "Duplicate file-prefix '" + job.filePrefix() + "' configured across jobs");
        }
        validateCron(job.cron(), prefix + ".cron");
        validateTimeZone(job.timeZone(), prefix + ".time-zone");
        validateTimestampPattern(job.timestampPattern(), prefix + ".timestamp-pattern");

        requireMin(job.fetchSize(), 1, prefix + ".fetch-size");
        requireMin(job.queryTimeoutSeconds(), 1, prefix + ".query-timeout-seconds");

        validateJobSource(job, prefix, allowedSqlDir);

        for (String paramName : job.parameters().keySet()) {
            validateIdentifier(paramName, prefix + ".parameters key");
        }

        validateCsvOptions(job.csv(), prefix + ".csv");

        requireNonBlank(job.sftpTarget(), prefix + ".sftp-target");
        if (job.enabled() && !sftpTargets.containsKey(job.sftpTarget())) {
            throw new IllegalArgumentException(
                    prefix + ".sftp-target references unknown target '" + job.sftpTarget() + "'");
        }

        if (isNotBlank(job.remoteDir())) {
            validateRemotePath(job.remoteDir(), prefix + ".remote-dir");
        }
    }

    private void validateJobSource(ExportJobConfig job, String prefix, String allowedSqlDir) {
        switch (job.sourceType()) {
            case STORED_PROCEDURE -> validateStoredProcedureSource(job, prefix);
            case SQL_QUERY -> validateSqlQuerySource(job, prefix, allowedSqlDir);
            case null -> throw new IllegalArgumentException(
                    prefix + ".source-type must be STORED_PROCEDURE or SQL_QUERY");
        }
    }

    private void validateStoredProcedureSource(ExportJobConfig job, String prefix) {
        if (isNotBlank(job.schemaName())) {
            validateIdentifier(job.schemaName(), prefix + ".schema-name");
        }
        if (isNotBlank(job.packageName())) {
            validateIdentifier(job.packageName(), prefix + ".package-name");
        }
        validateIdentifier(job.procedureName(), prefix + ".procedure-name");
        validateIdentifier(job.cursorParamName(), prefix + ".cursor-param-name");
    }

    private void validateSqlQuerySource(ExportJobConfig job, String prefix, String allowedSqlDir) {
        validateSqlFileLocation(job.sqlFile(), prefix + ".sql-file");
        if (job.enabled() && !resourceLoader.getResource(job.sqlFile()).exists()) {
            throw new IllegalArgumentException(
                    prefix + ".sql-file resource does not exist: " + job.sqlFile());
        }
        if (job.enabled() && job.sqlFile().startsWith("file:")) {
            validateExternalSqlFileSecurity(job.sqlFile(), allowedSqlDir);
        }
    }

    public void validateSftpTarget(String targetKey, SftpTargetConfig target, boolean allowInsecureSftpDevMode) {
        if (targetKey == null || !SAFE_TARGET_KEY_PATTERN.matcher(targetKey).matches()) {
            throw new IllegalArgumentException(
                    "Invalid SFTP target key: must match " + SAFE_TARGET_KEY_PATTERN.pattern());
        }
        String prefix = "app.sftp-targets." + targetKey;
        if (target == null) {
            throw new IllegalArgumentException(prefix + " must not be null");
        }
        requireNonBlank(target.host(), prefix + ".host");
        if (!SAFE_HOST_PATTERN.matcher(target.host()).matches()) {
            throw new IllegalArgumentException(
                    prefix + ".host contains invalid or unsafe characters");
        }
        if (target.port() < 1 || target.port() > 65535) {
            throw new IllegalArgumentException(prefix + ".port must be between 1 and 65535");
        }
        requireNonBlank(target.username(), prefix + ".username");
        if (!SAFE_USERNAME_PATTERN.matcher(target.username()).matches()) {
            throw new IllegalArgumentException(
                    prefix + ".username contains invalid or control characters");
        }

        validateSftpSecuritySettings(targetKey, target, prefix, allowInsecureSftpDevMode);

        if (!isNotBlank(target.tempSuffix())
                || !SAFE_TEMP_SUFFIX_PATTERN.matcher(target.tempSuffix()).matches()) {
            throw new IllegalArgumentException(
                    prefix + ".temp-suffix must match " + SAFE_TEMP_SUFFIX_PATTERN.pattern());
        }

        if (isNotBlank(target.remoteBaseDir())) {
            validateRemotePath(target.remoteBaseDir(), prefix + ".remote-base-dir");
        }

        if (target.connectTimeoutMs() < 1000 || target.authTimeoutMs() < 1000
                || target.channelOpenTimeoutMs() < 1000) {
            throw new IllegalArgumentException(prefix + " timeouts must be >= 1000 ms");
        }

        requireMin(target.maxConcurrentUploads(), 1, prefix + ".max-concurrent-uploads");
        validateRetryConfig(target.retry(), prefix + ".retry");
    }

    private void validateSftpSecuritySettings(
            String targetKey,
            SftpTargetConfig target,
            String prefix,
            boolean allowInsecureSftpDevMode) {
        if (!target.hasPrivateKeyAuth() && !target.hasPasswordAuth()) {
            throw new IllegalArgumentException(
                    prefix + " must configure either private-key-path (preferred) or password via environment variables");
        }

        if (target.strictHostKeyChecking()) {
            requireNonBlank(target.knownHostsPath(),
                    prefix + ".known-hosts-path (required when strict-host-key-checking=true)");
        } else if (!allowInsecureSftpDevMode) {
            throw new IllegalArgumentException(
                    prefix + ".strict-host-key-checking=false is prohibited unless app.allow-insecure-sftp-dev-mode=true");
        } else {
            log.warn(
                    "SECURITY WARNING: strict-host-key-checking is disabled for SFTP target '{}' (dev mode opt-in active). "
                            + "Do NOT disable host key verification in production!",
                    targetKey);
        }

        validateAlgorithmAllowlist(
                target.allowedCiphers(), SftpTargetConfig.APPROVED_STRONG_CIPHERS, prefix + ".allowed-ciphers");
        validateAlgorithmAllowlist(
                target.allowedKeyExchanges(), SftpTargetConfig.APPROVED_STRONG_KEX,
                prefix + ".allowed-key-exchanges");
        validateAlgorithmAllowlist(
                target.allowedMacs(), SftpTargetConfig.APPROVED_STRONG_MACS, prefix + ".allowed-macs");
    }

    private void validateAlgorithmAllowlist(List<String> configured, Set<String> approved, String fieldName) {
        requireNonEmptyList(configured, fieldName);
        for (String algorithm : configured) {
            if (algorithm == null || !approved.contains(algorithm)) {
                throw new IllegalArgumentException(
                        fieldName + " contains disallowed or weak SSH algorithm: '" + algorithm + "'");
            }
        }
    }

    private void validateRetryConfig(RetryConfig retry, String prefix) {
        if (retry == null) {
            throw new IllegalArgumentException(prefix + " must not be null");
        }
        if (retry.maxAttempts() < 1 || retry.maxAttempts() > MAX_RETRY_ATTEMPTS) {
            throw new IllegalArgumentException(
                    prefix + ".max-attempts must be between 1 and " + MAX_RETRY_ATTEMPTS);
        }
        if (retry.initialBackoffMs() < 0) {
            throw new IllegalArgumentException(prefix + ".initial-backoff-ms must be >= 0");
        }
        if (retry.multiplier() < 1.0) {
            throw new IllegalArgumentException(prefix + ".multiplier must be >= 1.0");
        }
        if (retry.maxBackoffMs() < retry.initialBackoffMs()
                || retry.maxBackoffMs() > MAX_RETRY_BACKOFF_MS) {
            throw new IllegalArgumentException(
                    prefix + ".max-backoff-ms must be >= initial-backoff-ms and <= " + MAX_RETRY_BACKOFF_MS);
        }
    }

    private void validateCsvOptions(CsvOptions csv, String prefix) {
        if (csv == null) {
            throw new IllegalArgumentException(prefix + " must not be null");
        }
        if (csv.delimiter() == null || csv.delimiter().length() != 1) {
            throw new IllegalArgumentException(prefix + ".delimiter must be exactly 1 character");
        }
        if (csv.quoteChar() != null && csv.quoteChar().length() > 1) {
            throw new IllegalArgumentException(prefix + ".quote-char must be empty or 1 character");
        }
        if (csv.recordSeparator() == null || csv.recordSeparator().isEmpty()) {
            throw new IllegalArgumentException(prefix + ".record-separator must not be empty");
        }
        requireNonBlank(csv.charset(), prefix + ".charset");
        if (!Charset.isSupported(csv.charset())) {
            throw new IllegalArgumentException(prefix + ".charset is not supported: " + csv.charset());
        }
        requireMin(csv.maxClobChars(), 1, prefix + ".max-clob-chars");
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
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + ex.getMessage(), ex);
        }
    }

    public static void validateIdentifier(String value, String fieldName) {
        if (value == null || !STRICT_IDENTIFIER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid " + fieldName + ": must strictly match " + STRICT_IDENTIFIER_PATTERN.pattern());
        }
    }

    public static void validateSqlFileLocation(String sqlFile, String fieldName) {
        requireNonBlank(sqlFile, fieldName);
        if (sqlFile.contains("..")
                || sqlFile.contains("\\")
                || sqlFile.contains("//")
                || !SAFE_SQL_RESOURCE_PATTERN.matcher(sqlFile).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe or invalid " + fieldName
                            + ": must be a safe classpath: or file: .sql resource without path traversal");
        }
    }

    public static void validateRemotePath(String remotePath, String fieldName) {
        if (remotePath == null) {
            return;
        }
        if (remotePath.contains("..")
                || remotePath.contains("\\")
                || remotePath.contains("//")
                || remotePath.equals(".")
                || remotePath.startsWith("./")
                || remotePath.endsWith("/.")
                || remotePath.contains("/./")
                || !SAFE_REMOTE_PATH_PATTERN.matcher(remotePath).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe " + fieldName + ": path traversal and control characters are prohibited");
        }
    }

    public static void validateSafeFileName(String fileName, String fieldName) {
        requireNonBlank(fileName, fieldName);
        if (".".equals(fileName)
                || "..".equals(fileName)
                || fileName.contains("..")
                || fileName.contains("/")
                || fileName.contains("\\")
                || !SAFE_FILENAME_PATTERN.matcher(fileName).matches()) {
            throw new IllegalArgumentException(
                    "Unsafe " + fieldName + ": must be a single safe filename without path separators");
        }
    }

    /**
     * Enforces symlink rejection, base-directory confinement (when
     * {@code allowedSqlDir} is configured),
     * and non-world-writability on external {@code file:} SQL resources, returning
     * the verified real path.
     */
    public static Path validateExternalSqlFileSecurity(String fileResourceUri, String allowedSqlDir) {
        Path rawPath = Path.of(fileResourceUri.substring("file:".length()));
        if (Files.isSymbolicLink(rawPath)) {
            throw new SecurityException("Refusing to execute symlink SQL file: " + rawPath.getFileName());
        }
        try {
            Path realSqlPath = rawPath.toRealPath();
            if (!Files.isRegularFile(realSqlPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("SQL path is not a regular file: " + realSqlPath.getFileName());
            }
            if (isNotBlank(allowedSqlDir)) {
                Path realSqlDir = Path.of(allowedSqlDir).toRealPath();
                ensureChildPath(realSqlDir, realSqlPath);
            }
            rejectWorldWritable(realSqlPath);
            return realSqlPath;
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "Unable to verify external SQL file security for '" + fileResourceUri + "': " + ex.getMessage(),
                    ex);
        }
    }

    private static void rejectWorldWritable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new SecurityException("Refusing to execute world-writable SQL file: " + path.getFileName());
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        }
    }

    /**
     * Defense-in-depth check ensuring a resolved local path stays strictly inside
     * its base directory
     * (rejecting both escaping paths and the base directory itself).
     */
    public static Path ensureChildPath(Path baseDir, Path candidate) {
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (normalizedCandidate.equals(normalizedBase) || !normalizedCandidate.startsWith(normalizedBase)) {
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
