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
