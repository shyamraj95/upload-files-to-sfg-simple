package com.enterprise.sfgexporter.db;

import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.model.CsvOptions;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingCsvWriterAndExtractorTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;
    private OracleDataExtractor extractor;

    @BeforeEach
    void setUp() {
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "sa", "");
        this.dataSource = ds;

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE test_transactions (
                    id INT PRIMARY KEY,
                    account_code VARCHAR(32),
                    amount DECIMAL(18, 2),
                    note VARCHAR(128),
                    status VARCHAR(16)
                )
                """);
        jdbc.update("INSERT INTO test_transactions VALUES (1, 'ACC_100', 12345.67, 'Normal row', 'SETTLED')");
        jdbc.update("INSERT INTO test_transactions VALUES (2, 'ACC_200', 99000000.00, 'Row, with \"quotes\"', 'SETTLED')");
        jdbc.update("INSERT INTO test_transactions VALUES (3, 'ACC_300', 50.00, NULL, 'SETTLED')");
        jdbc.update("INSERT INTO test_transactions VALUES (4, 'ACC_400', 75.00, 'Ignored pending', 'PENDING')");

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T06:15:30Z"), ZoneOffset.UTC);
        StreamingCsvWriter csvWriter = new StreamingCsvWriter(tempDir.resolve("staging"), fixedClock);
        this.extractor = new OracleDataExtractor(dataSource, new DefaultResourceLoader(), csvWriter);
    }

    @Test
    void streamsParameterizedSqlQueryToTimestampedCsv() throws Exception {
        ExportJobConfig job = new ExportJobConfig();
        job.setName("settlement_export");
        job.setSourceType(SourceType.SQL_QUERY);
        job.setSqlFile("classpath:sql/test_export.sql");
        job.setParameters(Map.of("status", "SETTLED"));
        job.setFilePrefix("PMT_SETTLEMENT");
        job.setTimeZone("UTC");

        CsvOptions csv = new CsvOptions();
        csv.setDelimiter("|");
        csv.setIncludeHeader(true);
        csv.setNullString("");
        job.setCsv(csv);

        StreamingCsvWriter.StagedCsvFile result = extractor.extractToCsv(job);

        assertThat(result.hasFile()).isTrue();
        assertThat(result.rowCount()).isEqualTo(3L);
        assertThat(result.fileName()).isEqualTo("PMT_SETTLEMENT_20261122_061530.csv");
        assertThat(Files.exists(result.filePath())).isTrue();

        List<String> lines = Files.readAllLines(result.filePath());
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("ID|ACCOUNT_CODE|AMOUNT|NOTE");
        assertThat(lines.get(1)).isEqualTo("1|ACC_100|12345.67|Normal row");
        assertThat(lines.get(2)).isEqualTo("2|ACC_200|99000000.00|\"Row, with \"\"quotes\"\"\"");
        assertThat(lines.get(3)).isEqualTo("3|ACC_300|50.00|");
    }

    @Test
    void skipsCreatingFileWhenZeroRowsAndWriteEmptyFileIsFalse() {
        ExportJobConfig job = new ExportJobConfig();
        job.setName("empty_export");
        job.setSourceType(SourceType.SQL_QUERY);
        job.setSqlFile("classpath:sql/test_export.sql");
        job.setParameters(Map.of("status", "NON_EXISTENT"));
        job.setFilePrefix("EMPTY_TEST");
        job.getCsv().setWriteEmptyFile(false);

        StreamingCsvWriter.StagedCsvFile result = extractor.extractToCsv(job);

        assertThat(result.hasFile()).isFalse();
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void buildsSafeNamedOracleProcedureCallSql() {
        ExportJobConfig job = new ExportJobConfig();
        job.setName("gl_proc");
        job.setSourceType(SourceType.STORED_PROCEDURE);
        job.setSchemaName("FIN_REPORTING");
        job.setPackageName("PKG_SFG_EXPORTS");
        job.setProcedureName("SP_EXPORT_DAILY_GL_JOURNALS");
        job.setCursorParamName("P_RESULT_CURSOR");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("P_LOOKBACK_DAYS", 1);
        params.put("P_LEDGER_CODE", "CORP_US");
        job.setParameters(params);

        String callSql = extractor.buildSafeProcedureCallSql(job);
        assertThat(callSql).isEqualTo(
                "{call FIN_REPORTING.PKG_SFG_EXPORTS.SP_EXPORT_DAILY_GL_JOURNALS("
                        + "P_LOOKBACK_DAYS => ?, P_LEDGER_CODE => ?, P_RESULT_CURSOR => ?)}");
    }
}
