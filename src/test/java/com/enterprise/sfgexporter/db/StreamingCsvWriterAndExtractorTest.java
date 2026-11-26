package com.enterprise.sfgexporter.db;

import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.model.CsvOptions;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreamingCsvWriterAndExtractorTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;
    private Path stagingDir;
    private StreamingCsvWriter csvWriter;
    private OracleDataExtractor extractor;

    @BeforeEach
    @SuppressWarnings("unused")
    void setUp() {
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "");
        this.dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "sa", "");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
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
        this.stagingDir = tempDir.resolve("staging");
        this.csvWriter = new StreamingCsvWriter(stagingDir, fixedClock);
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

    @Test
    void neutralizesCsvFormulaInjectionWhilePreservingNegativeBigDecimal() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(6);
        when(meta.getColumnLabel(1)).thenReturn("FORMULA");
        when(meta.getColumnLabel(2)).thenReturn("PLUS");
        when(meta.getColumnLabel(3)).thenReturn("MINUS_TEXT");
        when(meta.getColumnLabel(4)).thenReturn("AT_SIGN");
        when(meta.getColumnLabel(5)).thenReturn("TAB");
        when(meta.getColumnLabel(6)).thenReturn("AMOUNT");

        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("=SUM(A1)");
        when(rs.getObject(2)).thenReturn("+cmd");
        when(rs.getObject(3)).thenReturn("-1+1");
        when(rs.getObject(4)).thenReturn("@SUM(1+1)");
        when(rs.getObject(5)).thenReturn("\tcmd");
        when(rs.getObject(6)).thenReturn(new BigDecimal("-123.45"));

        ExportJobConfig job = new ExportJobConfig();
        job.setName("formula_test");
        job.setFilePrefix("FORMULA");

        StreamingCsvWriter.StagedCsvFile staged = csvWriter.writeResultSet(rs, job);
        List<String> lines = Files.readAllLines(staged.filePath());
        assertThat(lines.get(1)).isEqualTo("'=SUM(A1),'+cmd,'-1+1,'@SUM(1+1),'\tcmd,-123.45");
    }

    @Test
    void enforcesPosix0600PermissionsDuringStreamingAndAfterPromotion() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("COL");

        AtomicBoolean partHad0600 = new AtomicBoolean(false);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(rs.next()).thenAnswer(inv -> {
            if (firstCall.getAndSet(false)) {
                try (Stream<Path> files = Files.list(stagingDir)) {
                    Path partFile = files.filter(p -> p.toString().endsWith(".part")).findFirst().orElseThrow();
                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(partFile);
                    partHad0600.set(perms.equals(PosixFilePermissions.fromString("rw-------")));
                }
                return true;
            }
            return false;
        });
        when(rs.getObject(1)).thenReturn("VAL");

        ExportJobConfig job = new ExportJobConfig();
        job.setName("perm_test");
        job.setFilePrefix("PERM_TEST");

        StreamingCsvWriter.StagedCsvFile staged = csvWriter.writeResultSet(rs, job);
        assertThat(partHad0600).isTrue();
        assertThat(Files.getPosixFilePermissions(staged.filePath()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void rejectsOversizedClobAndFreesHandle() throws Exception {
        Clob clob = mock(Clob.class);
        when(clob.length()).thenReturn(2_000_000L);

        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("PAYLOAD");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(clob);

        ExportJobConfig job = new ExportJobConfig();
        job.setName("clob_limit");
        job.setFilePrefix("CLOB_LIMIT");

        assertThatThrownBy(() -> csvWriter.writeResultSet(rs, job))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("exceeds configured limit");
        verify(clob).free();
    }

    @Test
    void closesCallableStatementWhenParameterBindingThrows() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        CallableStatement mockCs = mock(CallableStatement.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareCall(anyString(), anyInt(), anyInt())).thenReturn(mockCs);
        doThrow(new SQLException("Invalid bind parameter")).when(mockCs).setObject(eq(1), any());

        OracleDataExtractor mockExtractor = new OracleDataExtractor(mockDs, new DefaultResourceLoader(), csvWriter);
        ExportJobConfig job = new ExportJobConfig();
        job.setName("proc_bind_fail");
        job.setSourceType(SourceType.STORED_PROCEDURE);
        job.setProcedureName("SP_FAIL");
        job.setParameters(Map.of("P_ID", "bad"));
        job.setFilePrefix("PROC_FAIL");

        assertThatThrownBy(() -> mockExtractor.extractToCsv(job))
                .isInstanceOf(DataAccessException.class);
        verify(mockCs).close();
    }
}
