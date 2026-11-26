package com.enterprise.sfgexporter;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.db.OracleDataExtractor;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import com.enterprise.sfgexporter.scheduler.BackupRetentionService;
import com.enterprise.sfgexporter.scheduler.DynamicJobScheduler;
import com.enterprise.sfgexporter.scheduler.ExportJobOrchestrator;
import com.enterprise.sfgexporter.sftp.AtomicSftpUploader;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the entire Spring Boot 4 application context wires all beans,
 * configuration records, validators, and schedulers cleanly.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ctx_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "APP_STAGING_DIR=${java.io.tmpdir}/sfg-ctx-test/staging",
        "APP_BACKUP_DIR=${java.io.tmpdir}/sfg-ctx-test/backup",
        "SFG_PRIMARY_HOST=sfg-test.internal",
        "SFG_PRIMARY_PORT=22",
        "SFG_PRIMARY_USER=svc_test",
        "SFG_SSH_KEY_PATH=/tmp/test_key",
        "SFG_KNOWN_HOSTS_PATH=/tmp/test_known_hosts"
})
class SfgExporterApplicationContextTest {

    @Autowired
    private StreamingCsvWriter csvWriter;

    @Autowired
    private OracleDataExtractor dataExtractor;

    @Autowired
    private AtomicSftpUploader sftpUploader;

    @Autowired
    private BackupRetentionService backupRetentionService;

    @Autowired
    private DynamicJobScheduler dynamicJobScheduler;

    @Autowired
    private ExportJobOrchestrator orchestrator;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private DataSource dataSource;

    /**
     * Verifies that the full Spring Boot application context starts cleanly, binds all
     * immutable {@code @ConfigurationProperties} records (including defaults and nested maps/lists),
     * and configures HikariCP Oracle socket/connect timeouts.
     */
    @Test
    void contextLoadsAndBindsConfigurationRecords() {
        assertThat(csvWriter).isNotNull();
        assertThat(dataExtractor).isNotNull();
        assertThat(sftpUploader).isNotNull();
        assertThat(backupRetentionService).isNotNull();
        assertThat(dynamicJobScheduler).isNotNull();
        assertThat(orchestrator).isNotNull();
        assertThat(appProperties.sftpTargets()).containsKeys("sfg-primary", "sfg-partner-b");
        assertThat(appProperties.sftpTargets().get("sfg-primary").strictHostKeyChecking()).isTrue();
        assertThat(appProperties.sftpTargets().get("sfg-primary").retry().maxAttempts()).isEqualTo(4);
        assertThat(appProperties.sftpTargets().get("sfg-partner-b").allowedCiphers())
                .isEqualTo(SftpTargetConfig.DEFAULT_STRONG_CIPHERS);
        assertThat(appProperties.jobs().getFirst().parameters())
                .containsKeys("P_LOOKBACK_DAYS", "P_LEDGER_CODE");
        assertThat(appProperties.jobs().getFirst().csv().neutralizeFormulas()).isTrue();
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getDataSourceProperties().get("oracle.jdbc.ReadTimeout")).hasToString("660000");
        assertThat(hikari.getDataSourceProperties().get("oracle.net.CONNECT_TIMEOUT")).hasToString("15000");
    }
}
