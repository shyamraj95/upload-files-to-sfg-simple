package com.enterprise.sfgexporter.config;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enterprise.sfgexporter.model.CsvOptions;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.RetryConfig;
import com.enterprise.sfgexporter.model.SecretValue;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import com.enterprise.sfgexporter.model.SourceType;

class SecurityValidatorTest {

        /**
         * Verifies that a valid configuration containing allowlisted SQL identifiers,
         * valid cron/timezone,
         * and hardened SFTP settings passes startup validation without throwing any
         * exception.
         */
        @Test
        void acceptsValidStoredProcedureAndSqlJobs() {
                AppProperties props = buildValidProperties();
                SecurityValidator validator = new SecurityValidator(props);
                assertThatCode(() -> validator.validateAll(props)).doesNotThrowAnyException();
                assertThatCode(() -> SecurityValidator.validateSqlFileLocation(
                                "file:D:/etc/sfg-exporter/sql/daily_gl.sql", "sqlFile")).doesNotThrowAnyException();
        }

        /**
         * Verifies that SQL injection payloads (semicolons, spaces, comments) in PL/SQL
         * procedure
         * identifiers are blocked at startup by the strict {@code ^\w+$} allowlist.
         */
        @Test
        void rejectsSqlInjectionInProcedureName() {
                AppProperties props = withJob(validJobBuilder().procedureName("SP_EXPORT; DROP TABLE USERS--").build());
                assertValidationFails(props, IllegalArgumentException.class, "procedure-name");
        }

        /**
         * Verifies that path-traversal sequences ({@code ../}) in a job's local CSV
         * {@code file-prefix}
         * are rejected so staged files cannot escape the staging directory.
         */
        @Test
        void rejectsPathTraversalInFilePrefix() {
                AppProperties props = withJob(validJobBuilder().filePrefix("../etc/passwd").build());
                assertValidationFails(props, IllegalArgumentException.class, "file-prefix");
        }

        /**
         * Verifies that relative parent traversal ({@code ..}) inside a job's SFTP
         * {@code remote-dir}
         * is rejected before any SFTP upload can target parent SFG mailboxes.
         */
        @Test
        void rejectsPathTraversalInRemoteDir() {
                AppProperties props = withJob(validJobBuilder().remoteDir("/inbound/../../etc").build());
                assertValidationFails(props, IllegalArgumentException.class, "remote-dir");
        }

        /**
         * Verifies that {@code known-hosts-path} is mandatory whenever
         * {@code strict-host-key-checking=true}.
         */
        @Test
        void rejectsMissingKnownHostsWhenStrictHostKeyCheckingEnabled() {
                AppProperties props = withTarget(validTargetBuilder().knownHostsPath("").build());
                assertValidationFails(props, IllegalArgumentException.class, "known-hosts-path");
        }

        /**
         * Verifies that an SFTP target referenced by an enabled job must configure
         * either SSH private-key
         * authentication or password authentication.
         */
        @Test
        void rejectsSftpTargetWithoutAnyAuthMethod() {
                AppProperties props = withTarget(validTargetBuilder()
                                .privateKeyPath(null)
                                .password((String) null)
                                .build());
                assertValidationFails(props, IllegalArgumentException.class, "private-key-path");
        }

        /**
         * Verifies that {@code ..} path traversal inside {@code sql-file} resource URIs
         * is rejected.
         */
        @Test
        void rejectsUnsafeSqlFileTraversal() {
                AppProperties props = withJob(validJobBuilder()
                                .sourceType(SourceType.SQL_QUERY)
                                .sqlFile("classpath:sql/../../secrets.sql")
                                .build());
                assertValidationFails(props, IllegalArgumentException.class, "sql-file");
        }

        /**
         * Verifies fail-fast startup detection when an enabled {@code SQL_QUERY} job
         * points to a
         * non-existent classpath or filesystem {@code .sql} resource.
         */
        @Test
        void rejectsMissingSqlFileForEnabledSqlJob() {
                AppProperties props = withJob(validJobBuilder()
                                .sourceType(SourceType.SQL_QUERY)
                                .sqlFile("classpath:sql/does_not_exist.sql")
                                .build());
                assertValidationFails(props, IllegalArgumentException.class, "does not exist");
        }

        /**
         * Verifies that configuring an empty cipher allowlist on an SFTP target is
         * rejected.
         */
        @Test
        void rejectsEmptyAllowedCiphersList() {
                AppProperties props = withTarget(validTargetBuilder().allowedCiphers(List.of()).build());
                assertValidationFails(props, IllegalArgumentException.class, "allowed-ciphers");
        }

        /**
         * Verifies that {@link SecurityValidator#ensureChildPath(Path, Path)} blocks
         * paths that resolve
         * outside the intended parent base directory.
         */
        @Test
        void ensureChildPathBlocksEscapingBaseDirectory(@TempDir Path tempDir) {
                Path escaped = tempDir.resolve("../outside.csv");
                assertThatThrownBy(() -> SecurityValidator.ensureChildPath(tempDir, escaped))
                                .isInstanceOf(SecurityException.class)
                                .hasMessageContaining("Path traversal");
        }

        /**
         * Verifies that {@link SecurityValidator#ensureChildPath(Path, Path)} rejects
         * the base directory
         * itself so callers never accidentally overwrite or delete the root directory.
         */
        @Test
        void ensureChildPathRejectsBaseDirectoryItself(@TempDir Path tempDir) {
                assertThatThrownBy(() -> SecurityValidator.ensureChildPath(tempDir, tempDir))
                                .isInstanceOf(SecurityException.class)
                                .hasMessageContaining("Path traversal");
        }

        /**
         * Verifies that two configured jobs cannot share the same {@code file-prefix},
         * preventing
         * concurrent filename collisions in {@code staging/} and {@code backup/}.
         */
        @Test
        void rejectsDuplicateFilePrefixAcrossJobs() {
                ExportJobConfig firstJob = validJobBuilder().build();
                ExportJobConfig secondJob = validJobBuilder().name("daily_gl_export_duplicate").build();
                AppProperties props = buildValidProperties().toBuilder()
                                .jobs(List.of(firstJob, secondJob))
                                .build();

                assertValidationFails(props, IllegalArgumentException.class, "Duplicate file-prefix");
        }

        /**
         * Verifies that disabling {@code strict-host-key-checking} is blocked by
         * default and is only
         * permitted when {@code app.allow-insecure-sftp-dev-mode=true} is explicitly
         * enabled.
         */
        @Test
        void rejectsDisabledStrictHostKeyCheckingWithoutDevModeOptIn() {
                AppProperties props = withTarget(validTargetBuilder().strictHostKeyChecking(false).build());
                assertValidationFails(props, IllegalArgumentException.class, "allow-insecure-sftp-dev-mode");

                AppProperties devModeProps = props.toBuilder().allowInsecureSftpDevMode(true).build();
                SecurityValidator devValidator = new SecurityValidator(devModeProps);
                assertThatCode(() -> devValidator.validateAll(devModeProps)).doesNotThrowAnyException();
        }

        /**
         * Verifies that external {@code file:} SQL scripts located outside the trusted
         * {@code app.storage.sql-dir} directory are rejected with
         * {@link SecurityException}.
         */
        @Test
        void rejectsExternalSqlFileOutsideAllowedSqlDir(@TempDir Path tempDir) throws Exception {
                Path allowedDir = Files.createDirectories(tempDir.resolve("allowed"));
                Path outsideSql = Files.writeString(tempDir.resolve("outside.sql"), "SELECT 1 FROM DUAL");

                AppProperties props = AppProperties.builder()
                                .storage(AppProperties.StorageConfig.builder().sqlDir(allowedDir.toString()).build())
                                .sftpTargets(Map.of("sfg-primary", validTargetBuilder().build()))
                                .jobs(List.of(validJobBuilder()
                                                .sourceType(SourceType.SQL_QUERY)
                                                .sqlFile(toFileResourceUri(outsideSql))
                                                .build()))
                                .build();

                assertValidationFails(props, SecurityException.class, "Path traversal");
        }

        /**
         * Verifies that world-writable external {@code file:} SQL scripts are rejected
         * so unprivileged
         * local users cannot tamper with scheduled SQL queries.
         */
        @Test
        void rejectsWorldWritableExternalSqlFile(@TempDir Path tempDir) throws Exception {
                Path sqlDir = Files.createDirectories(tempDir.resolve("sql"));
                Path sqlFile = Files.writeString(sqlDir.resolve("tamperable.sql"), "SELECT 1 FROM DUAL");
                try {
                        Files.setPosixFilePermissions(sqlFile, PosixFilePermissions.fromString("rw-rw-rw-"));
                } catch (UnsupportedOperationException ex) {
                        return; // Non-POSIX filesystem
                }

                AppProperties props = AppProperties.builder()
                                .storage(AppProperties.StorageConfig.builder().sqlDir(sqlDir.toString()).build())
                                .sftpTargets(Map.of("sfg-primary", validTargetBuilder().build()))
                                .jobs(List.of(validJobBuilder()
                                                .sourceType(SourceType.SQL_QUERY)
                                                .sqlFile(toFileResourceUri(sqlFile))
                                                .build()))
                                .build();

                assertValidationFails(props, SecurityException.class, "world-writable");
        }

        /**
         * Verifies that weak or unapproved SSH ciphers ({@code none}), key exchanges
         * ({@code diffie-hellman-group1-sha1}), and MACs ({@code hmac-md5}) are
         * rejected at startup.
         */
        @Test
        void rejectsWeakSshAlgorithms() {
                AppProperties props1 = withTarget(validTargetBuilder().allowedCiphers(List.of("none")).build());
                assertValidationFails(props1, IllegalArgumentException.class, "disallowed or weak");

                AppProperties props2 = withTarget(validTargetBuilder()
                                .allowedKeyExchanges(List.of("diffie-hellman-group1-sha1"))
                                .build());
                assertValidationFails(props2, IllegalArgumentException.class, "disallowed or weak");

                AppProperties props3 = withTarget(validTargetBuilder().allowedMacs(List.of("hmac-md5")).build());
                assertValidationFails(props3, IllegalArgumentException.class, "disallowed or weak");
        }

        /**
         * Verifies that {@code staging-dir} and {@code backup-dir} must be distinct,
         * non-nested directories
         * so retention sweeps cannot delete active staging files or vice versa.
         */
        @Test
        void rejectsOverlappingOrNestedStagingAndBackupDirs(@TempDir Path tempDir) {
                AppProperties sameDir = buildValidProperties().toBuilder()
                                .storage(AppProperties.StorageConfig.builder()
                                                .stagingDir(tempDir.toString())
                                                .backupDir(tempDir.toString())
                                                .build())
                                .build();
                assertValidationFails(sameDir, IllegalArgumentException.class, "distinct, non-nested");

                AppProperties nestedDir = buildValidProperties().toBuilder()
                                .storage(AppProperties.StorageConfig.builder()
                                                .stagingDir(tempDir.resolve("backup/staging").toString())
                                                .backupDir(tempDir.resolve("backup").toString())
                                                .build())
                                .build();
                assertValidationFails(nestedDir, IllegalArgumentException.class, "distinct, non-nested");
        }

        /**
         * Verifies SSRF/SMB protection against UNC {@code file://host/share} SQL URIs
         * and symlink rejection
         * for external SQL scripts inside {@code sql-dir}.
         */
        @Test
        void rejectsUncFileSqlUriAndSymlinkExternalSqlFile(@TempDir Path tempDir) throws Exception {
                AppProperties uncProps = withJob(validJobBuilder()
                                .sourceType(SourceType.SQL_QUERY)
                                .sqlFile("file://evil-host/share/query.sql")
                                .build());
                assertValidationFails(uncProps, IllegalArgumentException.class, "sql-file");

                Path sqlDir = Files.createDirectories(tempDir.resolve("sql"));
                Path targetSql = Files.writeString(tempDir.resolve("real.sql"), "SELECT 1 FROM DUAL");
                Path symlinkSql = createSymlinkOrSkip(sqlDir.resolve("link.sql"), targetSql);

                AppProperties symProps = AppProperties.builder()
                                .storage(AppProperties.StorageConfig.builder().sqlDir(sqlDir.toString()).build())
                                .sftpTargets(Map.of("sfg-primary", validTargetBuilder().build()))
                                .jobs(List.of(validJobBuilder()
                                                .sourceType(SourceType.SQL_QUERY)
                                                .sqlFile(toFileResourceUri(symlinkSql))
                                                .build()))
                                .build();

                assertValidationFails(symProps, SecurityException.class, "symlink");
        }

        /**
         * Verifies that empty/dot segments ({@code //}, {@code /./}) in remote paths
         * and newline/control
         * characters in SFTP hostnames are rejected.
         */
        @Test
        void rejectsUnsafeRemotePathSegmentsAndInvalidHost() {
                AppProperties badRemote = withJob(validJobBuilder().remoteDir("/finance//./gl").build());
                assertValidationFails(badRemote, IllegalArgumentException.class, "remote-dir");

                AppProperties badHost = withTarget(validTargetBuilder().host("sfg.example.com\nEvil").build());
                assertValidationFails(badHost, IllegalArgumentException.class, "host");
        }

        /**
         * Verifies that configuring a missing or symlinked {@code app.storage.sql-dir}
         * fails at startup.
         */
        @Test
        void rejectsInvalidOrSymlinkSqlDirAtStartup(@TempDir Path tempDir) throws Exception {
                AppProperties missingDirProps = buildValidProperties().toBuilder()
                                .storage(AppProperties.StorageConfig.builder()
                                                .sqlDir(tempDir.resolve("missing_sql_dir").toString())
                                                .build())
                                .build();
                assertValidationFails(missingDirProps, IllegalArgumentException.class, "app.storage.sql-dir");

                Path realDir = Files.createDirectories(tempDir.resolve("real_sql"));
                Path symlinkDir = createSymlinkOrSkip(tempDir.resolve("sym_sql"), realDir);
                AppProperties symDirProps = buildValidProperties().toBuilder()
                                .storage(AppProperties.StorageConfig.builder()
                                                .sqlDir(symlinkDir.toString())
                                                .build())
                                .build();
                assertValidationFails(symDirProps, IllegalArgumentException.class, "app.storage.sql-dir");
        }

        /**
         * Verifies remaining configuration boundary checks: duplicate job names,
         * unknown SFTP target references,
         * invalid cron/time-zone/timestamp patterns, invalid CSV options, and
         * out-of-range SFTP port, timeout,
         * username, and retry settings.
         */
        @Test
        void rejectsDuplicateJobNamesUnknownTargetInvalidSchedulesCsvAndRetryBounds() {
                // Duplicate job name
                ExportJobConfig job1 = validJobBuilder().build();
                ExportJobConfig job2 = validJobBuilder().filePrefix("GL_JOURNAL_2").build();
                AppProperties dupNameProps = buildValidProperties().toBuilder().jobs(List.of(job1, job2)).build();
                assertValidationFails(dupNameProps, IllegalArgumentException.class, "Duplicate job name");

                // Unknown SFTP target
                AppProperties unknownTargetProps = withJob(validJobBuilder().sftpTarget("non-existent").build());
                assertValidationFails(unknownTargetProps, IllegalArgumentException.class, "unknown target");

                // Invalid cron expression & invalid time-zone & unsafe slash in timestamp
                // pattern
                assertValidationFails(
                                withJob(validJobBuilder().cron("not-a-cron").build()),
                                IllegalArgumentException.class, "cron");
                assertValidationFails(
                                withJob(validJobBuilder().timeZone("Mars/Olympus").build()),
                                IllegalArgumentException.class, "time-zone");
                assertValidationFails(
                                withJob(validJobBuilder().timestampPattern("yyyy/MM/dd").build()),
                                IllegalArgumentException.class, "timestamp-pattern");

                // Invalid CSV delimiter (2 chars) & unsupported charset
                assertValidationFails(
                                withJob(validJobBuilder().csv(CsvOptions.builder().delimiter("||").build()).build()),
                                IllegalArgumentException.class, "delimiter");
                assertValidationFails(
                                withJob(validJobBuilder().csv(CsvOptions.builder().charset("NO_SUCH_CHARSET").build())
                                                .build()),
                                IllegalArgumentException.class, "charset");

                // Invalid SFTP port, control-char username, sub-1000ms timeout, and excessive
                // retry attempts
                assertValidationFails(
                                withTarget(validTargetBuilder().port(70000).build()),
                                IllegalArgumentException.class, "port");
                assertValidationFails(
                                withTarget(validTargetBuilder().username("bad user").build()),
                                IllegalArgumentException.class, "username");
                assertValidationFails(
                                withTarget(validTargetBuilder().connectTimeoutMs(500L).build()),
                                IllegalArgumentException.class, "timeouts");
                assertValidationFails(
                                withTarget(validTargetBuilder()
                                                .retry(RetryConfig.builder().maxAttempts(99).build())
                                                .build()),
                                IllegalArgumentException.class, "max-attempts");
        }

        /**
         * Verifies {@link SecretValue} credential redaction ({@code toString()} never
         * exposes plaintext
         * in record {@code toString()} output), explicit memory zeroing via
         * {@code clear()}, and
         * unmodifiable collection defensive copies on configuration records.
         */
        @Test
        void enforcesSecretValueRedactionZeroingAndConfigRecordImmutability() {
                SftpTargetConfig target = validTargetBuilder()
                                .password("SuperSecret,With,Commas!")
                                .privateKeyPassphrase("KeyPassphrase123")
                                .build();

                // Plaintext with commas is preserved intact (no Spring comma-splitting) yet
                // redacted in toString()
                assertThat(target.exposePassword()).isEqualTo("SuperSecret,With,Commas!");
                assertThat(target.password()).hasToString("***");
                assertThat(target.toString())
                                .doesNotContain("SuperSecret")
                                .doesNotContain("KeyPassphrase123")
                                .contains("authMode=");

                // Clearing secrets wipes both password and passphrase in place
                target.clearSecrets();
                assertThat(target.hasPasswordAuth()).isFalse();
                assertThat(target.exposePassword()).isNull();
                assertThat(target.exposePrivateKeyPassphrase()).isNull();

                SecretValue blankSecret = new SecretValue("   ");
                assertThat(blankSecret.isPresent()).isFalse();

                // Collections inside config records are strictly unmodifiable
                AppProperties props = buildValidProperties();
                List<ExportJobConfig> jobs = props.jobs();
                ExportJobConfig newJob = validJobBuilder().name("extra").build();
                assertThatThrownBy(() -> jobs.add(newJob))
                                .isInstanceOf(UnsupportedOperationException.class);

                List<String> ciphers = target.allowedCiphers();
                assertThatThrownBy(() -> ciphers.add("aes128-cbc"))
                                .isInstanceOf(UnsupportedOperationException.class);
        }

        /**
         * Verifies that primary/secondary SFTP targets accept either SSH private-key or
         * password-only
         * credentials, and that {@code sfg-partner-b} is completely optional (whether
         * left unconfigured
         * with blank values or omitted altogether) until an enabled job references it.
         */
        @Test
        void allowsOptionalSecondaryTargetUnconfiguredOrOmittedAndSupportsKeyOrPasswordAuth() {
                SftpTargetConfig primaryPasswordOnly = validTargetBuilder()
                                .privateKeyPath("")
                                .password("StrongPrimaryPass!1")
                                .build();
                ExportJobConfig primaryJob = validJobBuilder().enabled(true).sftpTarget("sfg-primary").build();
                ExportJobConfig disabledSecondaryJob = validJobBuilder()
                                .name("secondary_export")
                                .filePrefix("SEC_EXPORT")
                                .enabled(false)
                                .sftpTarget("sfg-partner-b")
                                .build();

                // 1. Password-only primary + blank/unconfigured optional secondary target ->
                // passes
                SftpTargetConfig blankOptionalTarget = SftpTargetConfig.builder().build();
                AppProperties blankSecondaryProps = AppProperties.builder()
                                .sftpTargets(Map.of(
                                                "sfg-primary", primaryPasswordOnly,
                                                "sfg-partner-b", blankOptionalTarget))
                                .jobs(List.of(primaryJob, disabledSecondaryJob))
                                .build();
                SecurityValidator validator1 = new SecurityValidator(blankSecondaryProps);
                assertThatCode(() -> validator1.validateAll(blankSecondaryProps)).doesNotThrowAnyException();

                // 2. Completely omitting optional secondary target when only referenced by
                // disabled job -> passes
                AppProperties omittedSecondaryProps = AppProperties.builder()
                                .sftpTargets(Map.of("sfg-primary", primaryPasswordOnly))
                                .jobs(List.of(primaryJob, disabledSecondaryJob))
                                .build();
                SecurityValidator validator2 = new SecurityValidator(omittedSecondaryProps);
                assertThatCode(() -> validator2.validateAll(omittedSecondaryProps)).doesNotThrowAnyException();

                // 3. Enabling the job referencing omitted or blank sfg-partner-b -> fails fast
                ExportJobConfig enabledSecondaryJob = disabledSecondaryJob.toBuilder().enabled(true).build();
                AppProperties enabledWithOmittedTarget = omittedSecondaryProps.toBuilder()
                                .jobs(List.of(primaryJob, enabledSecondaryJob))
                                .build();
                assertValidationFails(enabledWithOmittedTarget, IllegalArgumentException.class, "unknown target");

                AppProperties enabledWithBlankTarget = blankSecondaryProps.toBuilder()
                                .jobs(List.of(primaryJob, enabledSecondaryJob))
                                .build();
                assertValidationFails(enabledWithBlankTarget, IllegalArgumentException.class, "host");

                // 4. Configuring sfg-partner-b with password or SSH key once enabled -> passes
                AppProperties enabledWithPasswordTarget = AppProperties.builder()
                                .sftpTargets(Map.of(
                                                "sfg-primary", validTargetBuilder().build(),
                                                "sfg-partner-b", primaryPasswordOnly))
                                .jobs(List.of(primaryJob, enabledSecondaryJob))
                                .build();
                SecurityValidator validator3 = new SecurityValidator(enabledWithPasswordTarget);
                assertThatCode(() -> validator3.validateAll(enabledWithPasswordTarget)).doesNotThrowAnyException();
        }

        private void assertValidationFails(
                        AppProperties props,
                        Class<? extends Throwable> expectedType,
                        String expectedMessagePart) {
                SecurityValidator validator = new SecurityValidator(props);
                assertThatThrownBy(() -> validator.validateAll(props))
                                .isInstanceOf(expectedType)
                                .hasMessageContaining(expectedMessagePart);
        }

        private String toFileResourceUri(Path path) {
                return "file:" + path.toAbsolutePath().normalize().toString().replace('\\', '/');
        }

        private Path createSymlinkOrSkip(Path link, Path target) throws IOException {
                try {
                        return Files.createSymbolicLink(link, target);
                } catch (FileSystemException | UnsupportedOperationException ex) {
                        Assumptions.assumeTrue(false,
                                        "Skipping symlink assertion (OS privilege unavailable): " + ex.getMessage());
                        return null;
                }
        }

        private SftpTargetConfig.Builder validTargetBuilder() {
                return SftpTargetConfig.builder()
                                .host("sfg.example.com")
                                .port(22)
                                .username("svc_exporter")
                                .privateKeyPath("/etc/keys/id_ed25519")
                                .knownHostsPath("/etc/keys/known_hosts")
                                .strictHostKeyChecking(true);
        }

        private ExportJobConfig.Builder validJobBuilder() {
                return ExportJobConfig.builder()
                                .name("daily_gl_export")
                                .enabled(true)
                                .cron("0 0 6 * * *")
                                .timeZone("UTC")
                                .sourceType(SourceType.STORED_PROCEDURE)
                                .schemaName("FIN_REPORTING")
                                .packageName("PKG_EXPORTS")
                                .procedureName("SP_DAILY_GL")
                                .cursorParamName("P_RESULT_CURSOR")
                                .parameters(Map.of("P_DAYS", 1))
                                .filePrefix("GL_JOURNAL")
                                .sftpTarget("sfg-primary")
                                .remoteDir("/finance/gl");
        }

        private AppProperties withJob(ExportJobConfig job) {
                return buildValidProperties().toBuilder().jobs(List.of(job)).build();
        }

        private AppProperties withTarget(SftpTargetConfig target) {
                return buildValidProperties().toBuilder().sftpTargets(Map.of("sfg-primary", target)).build();
        }

        private AppProperties buildValidProperties() {
                return AppProperties.builder()
                                .sftpTargets(Map.of("sfg-primary", validTargetBuilder().build()))
                                .jobs(List.of(validJobBuilder().build()))
                                .build();
        }
}
