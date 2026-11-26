package com.enterprise.sfgexporter.config;

import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import com.enterprise.sfgexporter.model.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityValidatorTest {

    @Test
    void acceptsValidStoredProcedureAndSqlJobs() {
        AppProperties props = buildValidProperties();
        SecurityValidator validator = new SecurityValidator(props);
        assertThatCode(() -> validator.validateAll(props)).doesNotThrowAnyException();
    }

    @Test
    void rejectsSqlInjectionInProcedureName() {
        AppProperties props = buildValidProperties();
        props.getJobs().getFirst().setProcedureName("SP_EXPORT; DROP TABLE USERS--");

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("procedure-name");
    }

    @Test
    void rejectsPathTraversalInFilePrefix() {
        AppProperties props = buildValidProperties();
        props.getJobs().getFirst().setFilePrefix("../etc/passwd");

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("file-prefix");
    }

    @Test
    void rejectsPathTraversalInRemoteDir() {
        AppProperties props = buildValidProperties();
        props.getJobs().getFirst().setRemoteDir("/inbound/../../etc");

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote-dir");
    }

    @Test
    void rejectsMissingKnownHostsWhenStrictHostKeyCheckingEnabled() {
        AppProperties props = buildValidProperties();
        props.getSftpTargets().get("sfg-primary").setKnownHostsPath("");

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("known-hosts-path");
    }

    @Test
    void rejectsSftpTargetWithoutAnyAuthMethod() {
        AppProperties props = buildValidProperties();
        SftpTargetConfig target = props.getSftpTargets().get("sfg-primary");
        target.setPrivateKeyPath(null);
        target.setPassword(null);

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private-key-path");
    }

    @Test
    void rejectsUnsafeSqlFileTraversal() {
        AppProperties props = buildValidProperties();
        ExportJobConfig job = props.getJobs().getFirst();
        job.setSourceType(SourceType.SQL_QUERY);
        job.setSqlFile("classpath:sql/../../secrets.sql");

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql-file");
    }

    @Test
    void rejectsMissingSqlFileForEnabledSqlJob() {
        AppProperties props = buildValidProperties();
        ExportJobConfig job = props.getJobs().getFirst();
        job.setSourceType(SourceType.SQL_QUERY);
        job.setSqlFile("classpath:sql/does_not_exist.sql");

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsEmptyAllowedCiphersList() {
        AppProperties props = buildValidProperties();
        props.getSftpTargets().get("sfg-primary").setAllowedCiphers(List.of());

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-ciphers");
    }

    @Test
    void ensureChildPathBlocksEscapingBaseDirectory(@TempDir Path tempDir) {
        Path escaped = tempDir.resolve("../outside.csv");
        assertThatThrownBy(() -> SecurityValidator.ensureChildPath(tempDir, escaped))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal");
    }

    @Test
    void ensureChildPathRejectsBaseDirectoryItself(@TempDir Path tempDir) {
        assertThatThrownBy(() -> SecurityValidator.ensureChildPath(tempDir, tempDir))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal");
    }

    @Test
    void rejectsDuplicateFilePrefixAcrossJobs() {
        AppProperties props = buildValidProperties();
        ExportJobConfig secondJob = buildValidProperties().getJobs().getFirst();
        secondJob.setName("daily_gl_export_duplicate");
        props.setJobs(List.of(props.getJobs().getFirst(), secondJob));

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate file-prefix");
    }

    @Test
    void rejectsDisabledStrictHostKeyCheckingWithoutDevModeOptIn() {
        AppProperties props = buildValidProperties();
        props.getSftpTargets().get("sfg-primary").setStrictHostKeyChecking(false);

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow-insecure-sftp-dev-mode");

        props.setAllowInsecureSftpDevMode(true);
        assertThatCode(() -> validator.validateAll(props)).doesNotThrowAnyException();
    }

    @Test
    void rejectsExternalSqlFileOutsideAllowedSqlDir(@TempDir Path tempDir) throws Exception {
        Path allowedDir = Files.createDirectories(tempDir.resolve("allowed"));
        Path outsideSql = Files.writeString(tempDir.resolve("outside.sql"), "SELECT 1 FROM DUAL");

        AppProperties props = buildValidProperties();
        props.getStorage().setSqlDir(allowedDir.toString());
        ExportJobConfig job = props.getJobs().getFirst();
        job.setSourceType(SourceType.SQL_QUERY);
        job.setSqlFile("file:" + outsideSql.toAbsolutePath());

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal");
    }

    @Test
    void rejectsWorldWritableExternalSqlFile(@TempDir Path tempDir) throws Exception {
        Path sqlDir = Files.createDirectories(tempDir.resolve("sql"));
        Path sqlFile = Files.writeString(sqlDir.resolve("tamperable.sql"), "SELECT 1 FROM DUAL");
        try {
            Files.setPosixFilePermissions(sqlFile, PosixFilePermissions.fromString("rw-rw-rw-"));
        } catch (UnsupportedOperationException ex) {
            return; // Non-POSIX filesystem
        }

        AppProperties props = buildValidProperties();
        props.getStorage().setSqlDir(sqlDir.toString());
        ExportJobConfig job = props.getJobs().getFirst();
        job.setSourceType(SourceType.SQL_QUERY);
        job.setSqlFile("file:" + sqlFile.toAbsolutePath());

        SecurityValidator validator = new SecurityValidator(props);
        assertThatThrownBy(() -> validator.validateAll(props))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("world-writable");
    }

    @Test
    void rejectsWeakSshAlgorithms() {
        AppProperties props1 = buildValidProperties();
        props1.getSftpTargets().get("sfg-primary").setAllowedCiphers(List.of("none"));
        assertThatThrownBy(() -> new SecurityValidator(props1).validateAll(props1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed or weak");

        AppProperties props2 = buildValidProperties();
        props2.getSftpTargets().get("sfg-primary").setAllowedKeyExchanges(List.of("diffie-hellman-group1-sha1"));
        assertThatThrownBy(() -> new SecurityValidator(props2).validateAll(props2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed or weak");

        AppProperties props3 = buildValidProperties();
        props3.getSftpTargets().get("sfg-primary").setAllowedMacs(List.of("hmac-md5"));
        assertThatThrownBy(() -> new SecurityValidator(props3).validateAll(props3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disallowed or weak");
    }

    @Test
    void rejectsOverlappingOrNestedStagingAndBackupDirs(@TempDir Path tempDir) {
        AppProperties sameDir = buildValidProperties();
        sameDir.getStorage().setStagingDir(tempDir.toString());
        sameDir.getStorage().setBackupDir(tempDir.toString());
        assertThatThrownBy(() -> new SecurityValidator(sameDir).validateAll(sameDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct, non-nested");

        AppProperties nestedDir = buildValidProperties();
        nestedDir.getStorage().setStagingDir(tempDir.resolve("backup/staging").toString());
        nestedDir.getStorage().setBackupDir(tempDir.resolve("backup").toString());
        assertThatThrownBy(() -> new SecurityValidator(nestedDir).validateAll(nestedDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("distinct, non-nested");
    }

    @Test
    void rejectsUncFileSqlUriAndSymlinkExternalSqlFile(@TempDir Path tempDir) throws Exception {
        AppProperties uncProps = buildValidProperties();
        ExportJobConfig uncJob = uncProps.getJobs().getFirst();
        uncJob.setSourceType(SourceType.SQL_QUERY);
        uncJob.setSqlFile("file://evil-host/share/query.sql");
        assertThatThrownBy(() -> new SecurityValidator(uncProps).validateAll(uncProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql-file");

        Path sqlDir = Files.createDirectories(tempDir.resolve("sql"));
        Path targetSql = Files.writeString(tempDir.resolve("real.sql"), "SELECT 1 FROM DUAL");
        Path symlinkSql = Files.createSymbolicLink(sqlDir.resolve("link.sql"), targetSql);

        AppProperties symProps = buildValidProperties();
        symProps.getStorage().setSqlDir(sqlDir.toString());
        ExportJobConfig symJob = symProps.getJobs().getFirst();
        symJob.setSourceType(SourceType.SQL_QUERY);
        symJob.setSqlFile("file:" + symlinkSql.toAbsolutePath());

        assertThatThrownBy(() -> new SecurityValidator(symProps).validateAll(symProps))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void rejectsUnsafeRemotePathSegmentsAndInvalidHost() {
        AppProperties badRemote = buildValidProperties();
        badRemote.getJobs().getFirst().setRemoteDir("/finance//./gl");
        assertThatThrownBy(() -> new SecurityValidator(badRemote).validateAll(badRemote))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remote-dir");

        AppProperties badHost = buildValidProperties();
        badHost.getSftpTargets().get("sfg-primary").setHost("sfg.example.com\nEvil");
        assertThatThrownBy(() -> new SecurityValidator(badHost).validateAll(badHost))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void rejectsInvalidOrSymlinkSqlDirAtStartup(@TempDir Path tempDir) throws Exception {
        AppProperties missingDirProps = buildValidProperties();
        missingDirProps.getStorage().setSqlDir(tempDir.resolve("missing_sql_dir").toString());
        assertThatThrownBy(() -> new SecurityValidator(missingDirProps).validateAll(missingDirProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.storage.sql-dir");

        Path realDir = Files.createDirectories(tempDir.resolve("real_sql"));
        Path symlinkDir = Files.createSymbolicLink(tempDir.resolve("sym_sql"), realDir);
        AppProperties symDirProps = buildValidProperties();
        symDirProps.getStorage().setSqlDir(symlinkDir.toString());
        assertThatThrownBy(() -> new SecurityValidator(symDirProps).validateAll(symDirProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.storage.sql-dir");
    }

    private AppProperties buildValidProperties() {
        SftpTargetConfig target = new SftpTargetConfig();
        target.setHost("sfg.example.com");
        target.setPort(22);
        target.setUsername("svc_exporter");
        target.setPrivateKeyPath("/etc/keys/id_ed25519");
        target.setKnownHostsPath("/etc/keys/known_hosts");
        target.setStrictHostKeyChecking(true);

        ExportJobConfig job = new ExportJobConfig();
        job.setName("daily_gl_export");
        job.setEnabled(true);
        job.setCron("0 0 6 * * *");
        job.setTimeZone("UTC");
        job.setSourceType(SourceType.STORED_PROCEDURE);
        job.setSchemaName("FIN_REPORTING");
        job.setPackageName("PKG_EXPORTS");
        job.setProcedureName("SP_DAILY_GL");
        job.setCursorParamName("P_RESULT_CURSOR");
        job.setParameters(Map.of("P_DAYS", 1));
        job.setFilePrefix("GL_JOURNAL");
        job.setSftpTarget("sfg-primary");
        job.setRemoteDir("/finance/gl");

        AppProperties props = new AppProperties();
        props.setSftpTargets(Map.of("sfg-primary", target));
        props.setJobs(List.of(job));
        return props;
    }
}
