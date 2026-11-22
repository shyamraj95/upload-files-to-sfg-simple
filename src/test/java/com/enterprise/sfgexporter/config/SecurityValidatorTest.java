package com.enterprise.sfgexporter.config;

import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import com.enterprise.sfgexporter.model.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
