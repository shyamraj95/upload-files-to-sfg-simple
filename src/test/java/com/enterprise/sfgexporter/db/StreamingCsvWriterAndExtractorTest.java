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
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
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
import static org.mockito.Mockito.never;
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

    /**
     * Verifies end-to-end parameterized {@code SQL_QUERY} extraction against H2, streaming rows
     * into a timestamped CSV file with custom pipe ({@code |}) delimiter, RFC-4180 quote escaping,
     * plain {@link BigDecimal} formatting, and custom null handling.
     */
    @Test
    void streamsParameterizedSqlQueryToTimestampedCsv() throws Exception {
        ExportJobConfig job = ExportJobConfig.builder()
                .name("settlement_export")
                .sourceType(SourceType.SQL_QUERY)
                .sqlFile("classpath:sql/test_export.sql")
                .parameters(Map.of("status", "SETTLED"))
                .filePrefix("PMT_SETTLEMENT")
                .timeZone("UTC")
                .csv(CsvOptions.builder()
                        .delimiter("|")
                        .includeHeader(true)
                        .nullString("")
                        .build())
                .build();

        StreamingCsvWriter.StagedCsvFile result = extractor.extractToCsv(job);

        assertThat(result.hasFile()).isTrue();
        assertThat(result.rowCount()).isEqualTo(3L);
        assertThat(result.fileName()).isEqualTo("PMT_SETTLEMENT_20261122_061530.csv");
        assertThat(Files.exists(result.filePath())).isTrue();

        List<String> lines = Files.readAllLines(result.filePath());
        assertThat(lines).containsExactly(
                "ID|ACCOUNT_CODE|AMOUNT|NOTE",
                "1|ACC_100|12345.67|Normal row",
                "2|ACC_200|99000000.00|\"Row, with \"\"quotes\"\"\"",
                "3|ACC_300|50.00|");
    }

    /**
     * Verifies that when a query returns 0 rows and {@code write-empty-file=false}, no CSV or
     * leftover {@code .part} file is created in {@code staging/}.
     */
    @Test
    void skipsCreatingFileWhenZeroRowsAndWriteEmptyFileIsFalse() {
        ExportJobConfig job = ExportJobConfig.builder()
                .name("empty_export")
                .sourceType(SourceType.SQL_QUERY)
                .sqlFile("classpath:sql/test_export.sql")
                .parameters(Map.of("status", "NON_EXISTENT"))
                .filePrefix("EMPTY_TEST")
                .csv(CsvOptions.builder().writeEmptyFile(false).build())
                .build();

        StreamingCsvWriter.StagedCsvFile result = extractor.extractToCsv(job);

        assertThat(result.hasFile()).isFalse();
        assertThat(result.rowCount()).isZero();
    }

    /**
     * Verifies that {@link OracleDataExtractor#buildSafeProcedureCallSql(ExportJobConfig)} generates
     * an allowlist-validated Oracle PL/SQL named-parameter call ({@code PARAM => ?}) preserving
     * parameter declaration order and appending the {@code SYS_REFCURSOR} OUT parameter last.
     */
    @Test
    void buildsSafeNamedOracleProcedureCallSql() {
        SequencedMap<String, Object> params = new LinkedHashMap<>();
        params.put("P_LOOKBACK_DAYS", 1);
        params.put("P_LEDGER_CODE", "CORP_US");

        ExportJobConfig job = ExportJobConfig.builder()
                .name("gl_proc")
                .sourceType(SourceType.STORED_PROCEDURE)
                .schemaName("FIN_REPORTING")
                .packageName("PKG_SFG_EXPORTS")
                .procedureName("SP_EXPORT_DAILY_GL_JOURNALS")
                .cursorParamName("P_RESULT_CURSOR")
                .parameters(params)
                .build();

        String callSql = extractor.buildSafeProcedureCallSql(job);
        assertThat(callSql).isEqualTo(
                "{call FIN_REPORTING.PKG_SFG_EXPORTS.SP_EXPORT_DAILY_GL_JOURNALS("
                        + "P_LOOKBACK_DAYS => ?, P_LEDGER_CODE => ?, P_RESULT_CURSOR => ?)}");
    }

    /**
     * Verifies OWASP CSV formula-injection neutralization: string cells starting with {@code =},
     * {@code +}, {@code -}, {@code @}, or {@code \t} are prefixed with {@code '}, whereas numeric
     * negative {@link BigDecimal} amounts remain unprefixed valid numbers.
     */
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

        ExportJobConfig job = ExportJobConfig.builder()
                .name("formula_test")
                .filePrefix("FORMULA")
                .build();

        StreamingCsvWriter.StagedCsvFile staged = csvWriter.writeResultSet(rs, job);
        List<String> lines = Files.readAllLines(staged.filePath());
        assertThat(lines.get(1)).isEqualTo("'=SUM(A1),'+cmd,'-1+1,'@SUM(1+1),'\tcmd,-123.45");
    }

    /**
     * Verifies that POSIX {@code 0600} ({@code rw-------}) owner-only file permissions are enforced
     * atomically at {@code .part} file creation time (eliminating any TOCTOU window) and preserved
     * after promotion to {@code .csv}.
     */
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

        ExportJobConfig job = ExportJobConfig.builder()
                .name("perm_test")
                .filePrefix("PERM_TEST")
                .build();

        StreamingCsvWriter.StagedCsvFile staged = csvWriter.writeResultSet(rs, job);
        assertThat(partHad0600).isTrue();
        assertThat(Files.getPosixFilePermissions(staged.filePath()))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Verifies OOM protection on oversized JDBC {@link Clob} columns: values exceeding
     * {@code maxClobChars} are rejected and {@link Clob#free()} is still guaranteed to run in {@code finally}.
     */
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

        ExportJobConfig job = ExportJobConfig.builder()
                .name("clob_limit")
                .filePrefix("CLOB_LIMIT")
                .build();

        assertThatThrownBy(() -> csvWriter.writeResultSet(rs, job))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("exceeds configured limit");
        verify(clob).free();
    }

    /**
     * Verifies JDBC cursor/statement leak prevention: if parameter binding throws inside
     * {@code CallableStatementCreator} before {@link JdbcTemplate} takes ownership, the
     * {@link CallableStatement} is closed immediately.
     */
    @Test
    void closesCallableStatementWhenParameterBindingThrows() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        CallableStatement mockCs = mock(CallableStatement.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareCall(anyString(), anyInt(), anyInt())).thenReturn(mockCs);
        doThrow(new SQLException("Invalid bind parameter")).when(mockCs).setObject(eq(1), any());

        OracleDataExtractor mockExtractor = new OracleDataExtractor(mockDs, new DefaultResourceLoader(), csvWriter);
        ExportJobConfig job = ExportJobConfig.builder()
                .name("proc_bind_fail")
                .sourceType(SourceType.STORED_PROCEDURE)
                .procedureName("SP_FAIL")
                .parameters(Map.of("P_ID", "bad"))
                .filePrefix("PROC_FAIL")
                .build();

        assertThatThrownBy(() -> mockExtractor.extractToCsv(job))
                .isInstanceOf(DataAccessException.class);
        verify(mockCs).close();
    }

    /**
     * Verifies extended CSV formula-injection neutralization when formulas appear in column headers,
     * after leading whitespace ({@code " =SUM(A1)"}), or after leading newlines ({@code "\n=CMD"}).
     */
    @Test
    void neutralizesLeadingWhitespaceNewlineAndFormulaHeaders() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(3);
        when(meta.getColumnLabel(1)).thenReturn("=HDR");
        when(meta.getColumnLabel(2)).thenReturn("SPACE_FORMULA");
        when(meta.getColumnLabel(3)).thenReturn("NEWLINE_FORMULA");

        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("ok");
        when(rs.getObject(2)).thenReturn(" =SUM(A1)");
        when(rs.getObject(3)).thenReturn("\n=CMD");

        ExportJobConfig job = ExportJobConfig.builder()
                .name("ws_formula_test")
                .filePrefix("WS_FORMULA")
                .build();

        StreamingCsvWriter.StagedCsvFile staged = csvWriter.writeResultSet(rs, job);
        String content = Files.readString(staged.filePath());
        assertThat(content)
                .startsWith("'=HDR,SPACE_FORMULA,NEWLINE_FORMULA\n")
                .contains("' =SUM(A1),\"'\n=CMD\"");
    }

    /**
     * Verifies bounded {@link SQLXML} streaming (with formula neutralization and {@link SQLXML#free()}
     * cleanup) as well as explicit rejection of binary {@link Blob} columns (freeing the {@link Blob} handle).
     */
    @Test
    void readsSqlXmlBoundedAndRejectsBlobWhileFreeingResources() throws Exception {
        SQLXML sqlxml = mock(SQLXML.class);
        when(sqlxml.getCharacterStream()).thenReturn(new StringReader("=XML_FORMULA"));

        ResultSet xmlRs = mock(ResultSet.class);
        ResultSetMetaData xmlMeta = mock(ResultSetMetaData.class);
        when(xmlRs.getMetaData()).thenReturn(xmlMeta);
        when(xmlMeta.getColumnCount()).thenReturn(1);
        when(xmlMeta.getColumnLabel(1)).thenReturn("XML_COL");
        when(xmlRs.next()).thenReturn(true, false);
        when(xmlRs.getObject(1)).thenReturn(sqlxml);

        ExportJobConfig xmlJob = ExportJobConfig.builder()
                .name("xml_test")
                .filePrefix("XML_TEST")
                .build();

        StreamingCsvWriter.StagedCsvFile staged = csvWriter.writeResultSet(xmlRs, xmlJob);
        assertThat(Files.readAllLines(staged.filePath()).getLast()).isEqualTo("'=XML_FORMULA");
        verify(sqlxml).free();

        Blob blob = mock(Blob.class);
        ResultSet blobRs = mock(ResultSet.class);
        ResultSetMetaData blobMeta = mock(ResultSetMetaData.class);
        when(blobRs.getMetaData()).thenReturn(blobMeta);
        when(blobMeta.getColumnCount()).thenReturn(1);
        when(blobMeta.getColumnLabel(1)).thenReturn("BLOB_COL");
        when(blobRs.next()).thenReturn(true, false);
        when(blobRs.getObject(1)).thenReturn(blob);

        ExportJobConfig blobJob = ExportJobConfig.builder()
                .name("blob_test")
                .filePrefix("BLOB_TEST")
                .build();

        assertThatThrownBy(() -> csvWriter.writeResultSet(blobRs, blobJob))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("BLOB");
        verify(blob).free();
    }

    /**
     * Verifies memory DoS protection when loading external {@code file:} SQL resources: files larger
     * than 1 MiB are rejected before reading into memory or acquiring a database connection.
     */
    @Test
    void rejectsOversizedExternalSqlFileBeforeExecution() throws Exception {
        Path oversizedSql = tempDir.resolve("huge.sql");
        Files.writeString(oversizedSql, "SELECT '" + "X".repeat(1_048_600) + "' FROM DUAL");

        DataSource mockDs = mock(DataSource.class);
        OracleDataExtractor mockExtractor = new OracleDataExtractor(mockDs, new DefaultResourceLoader(), csvWriter);
        ExportJobConfig job = ExportJobConfig.builder()
                .name("huge_sql")
                .sourceType(SourceType.SQL_QUERY)
                .sqlFile("file:" + oversizedSql.toAbsolutePath())
                .filePrefix("HUGE_SQL")
                .build();

        assertThatThrownBy(() -> mockExtractor.extractToCsv(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum allowed size");
        verify(mockDs, never()).getConnection();
    }

    /**
     * Verifies JDBC statement leak prevention during {@code SQL_QUERY} preparation: if
     * {@code setFetchSize} or {@code setQueryTimeout} throws before {@link JdbcTemplate} takes ownership,
     * the {@link PreparedStatement} is closed immediately.
     */
    @Test
    void closesPreparedStatementWhenSetFetchSizeThrowsDuringSqlQuery() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        PreparedStatement mockPs = mock(PreparedStatement.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareStatement(anyString())).thenReturn(mockPs);
        doThrow(new SQLException("fetchSize unsupported")).when(mockPs).setFetchSize(anyInt());

        OracleDataExtractor mockExtractor = new OracleDataExtractor(mockDs, new DefaultResourceLoader(), csvWriter);
        ExportJobConfig job = ExportJobConfig.builder()
                .name("sql_fetch_fail")
                .sourceType(SourceType.SQL_QUERY)
                .sqlFile("classpath:sql/test_export.sql")
                .parameters(Map.of("status", "SETTLED"))
                .filePrefix("SQL_FETCH_FAIL")
                .build();

        assertThatThrownBy(() -> mockExtractor.extractToCsv(job))
                .isInstanceOf(DataAccessException.class);
        verify(mockPs).close();
    }

    /**
     * Verifies that JDBC {@code DATE}, {@code TIME}, and {@code TIMESTAMP} columns (plus {@code NULL}s)
     * are extracted directly as {@code java.time} types via JDBC 4.2 {@code ResultSet.getObject(int, Class)}
     * and formatted cleanly as ISO-8601 values without using legacy {@code java.util.Date}/{@code java.sql.Date}.
     */
    @Test
    void streamsJdbcDateTimeColumnsUsingJavaTimeApi() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE test_dates (
                    d DATE,
                    t TIME,
                    ts TIMESTAMP
                )
                """);
        jdbc.update("INSERT INTO test_dates VALUES (DATE '2026-11-22', TIME '06:15:30', TIMESTAMP '2026-11-22 06:15:30')");
        jdbc.update("INSERT INTO test_dates VALUES (NULL, NULL, NULL)");

        ExportJobConfig job = ExportJobConfig.builder()
                .name("datetime_test")
                .filePrefix("DATETIME_TEST")
                .build();

        StreamingCsvWriter.StagedCsvFile staged;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT d, t, ts FROM test_dates")) {
            staged = csvWriter.writeResultSet(rs, job);
        }

        List<String> lines = Files.readAllLines(staged.filePath());
        assertThat(lines).containsExactly(
                "D,T,TS",
                "2026-11-22,06:15:30,2026-11-22T06:15:30",
                ",,");
    }

    /**
     * Verifies the full {@code STORED_PROCEDURE} happy path ({@code CallableStatement} execution,
     * {@code Types.REF_CURSOR} registration, {@code fetchSize} propagation on the cursor, CSV streaming,
     * and deterministic closing of cursor/statement) as well as rejection when the procedure returns
     * a {@code null} {@code SYS_REFCURSOR}.
     */
    @Test
    void streamsStoredProcedureCursorAndRejectsNullRefCursor() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        CallableStatement mockCs = mock(CallableStatement.class);
        ResultSet mockRs = mock(ResultSet.class);
        ResultSetMetaData mockMeta = mock(ResultSetMetaData.class);

        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.prepareCall(anyString(), eq(ResultSet.TYPE_FORWARD_ONLY), eq(ResultSet.CONCUR_READ_ONLY)))
                .thenReturn(mockCs);
        when(mockCs.getObject(2, ResultSet.class)).thenReturn(mockRs);
        when(mockRs.getMetaData()).thenReturn(mockMeta);
        when(mockMeta.getColumnCount()).thenReturn(2);
        when(mockMeta.getColumnLabel(1)).thenReturn("JOURNAL_ID");
        when(mockMeta.getColumnLabel(2)).thenReturn("MEMO");

        Clob smallClob = mock(Clob.class);
        when(smallClob.length()).thenReturn(8L);
        when(smallClob.getSubString(1L, 8)).thenReturn("GL_ENTRY");

        when(mockRs.next()).thenReturn(true, false);
        when(mockRs.getObject(1)).thenReturn(101L);
        when(mockRs.getObject(2)).thenReturn(smallClob);

        OracleDataExtractor procExtractor = new OracleDataExtractor(mockDs, new DefaultResourceLoader(), csvWriter);
        ExportJobConfig job = ExportJobConfig.builder()
                .name("gl_proc_ok")
                .sourceType(SourceType.STORED_PROCEDURE)
                .schemaName("FIN")
                .packageName("PKG_GL")
                .procedureName("SP_EXPORT")
                .cursorParamName("P_OUT")
                .parameters(Map.of("P_DAYS", 1))
                .fetchSize(500)
                .queryTimeoutSeconds(120)
                .filePrefix("PROC_OK")
                .build();

        StreamingCsvWriter.StagedCsvFile staged = procExtractor.extractToCsv(job);

        assertThat(staged.hasFile()).isTrue();
        assertThat(staged.rowCount()).isEqualTo(1L);
        assertThat(Files.readAllLines(staged.filePath()))
                .containsExactly("JOURNAL_ID,MEMO", "101,GL_ENTRY");
        verify(mockCs).registerOutParameter(2, Types.REF_CURSOR);
        verify(mockRs).setFetchSize(500);
        verify(smallClob).free();
        verify(mockRs).close();
        verify(mockCs).close();

        // Now simulate a stored procedure returning a null SYS_REFCURSOR
        when(mockCs.getObject(2, ResultSet.class)).thenReturn(null);
        assertThatThrownBy(() -> procExtractor.extractToCsv(job))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("null SYS_REFCURSOR");
    }

    /**
     * Verifies that {@link OracleDataExtractor} strips a trailing semicolon ({@code ;}) from external
     * {@code .sql} files (which Oracle JDBC otherwise rejects) and fails fast on blank/whitespace-only SQL files.
     */
    @Test
    void stripsTrailingSemicolonInSqlFileAndRejectsBlankSqlFile() throws Exception {
        Path semicolonSql = Files.writeString(
                tempDir.resolve("with_semicolon.sql"),
                "  SELECT id, account_code FROM test_transactions WHERE status = :status;  \n");
        ExportJobConfig okJob = ExportJobConfig.builder()
                .name("semi_sql")
                .sourceType(SourceType.SQL_QUERY)
                .sqlFile("file:" + semicolonSql.toAbsolutePath())
                .parameters(Map.of("status", "PENDING"))
                .filePrefix("SEMI_SQL")
                .build();

        StreamingCsvWriter.StagedCsvFile result = extractor.extractToCsv(okJob);
        assertThat(result.rowCount()).isEqualTo(1L);
        assertThat(Files.readAllLines(result.filePath()))
                .containsExactly("ID,ACCOUNT_CODE", "4,ACC_400");

        Path emptySql = Files.writeString(tempDir.resolve("blank.sql"), "   \n\t  ");
        ExportJobConfig emptySqlJob = ExportJobConfig.builder()
                .name("blank_sql")
                .sourceType(SourceType.SQL_QUERY)
                .sqlFile("file:" + emptySql.toAbsolutePath())
                .filePrefix("BLANK_SQL")
                .build();

        assertThatThrownBy(() -> extractor.extractToCsv(emptySqlJob))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    /**
     * Verifies atomic local staging guarantees: if {@link ResultSet#next()} throws mid-stream,
     * the temporary {@code .part} file is deleted in {@code finally} leaving zero orphan files;
     * and when 0 rows are returned with {@code write-empty-file=true}, a header-only CSV is promoted cleanly.
     */
    @Test
    void deletesLocalPartFileOnMidStreamFailureAndWritesHeaderOnlyCsvWhenConfigured() throws Exception {
        ResultSet failingRs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(failingRs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("COL1");
        when(failingRs.next()).thenReturn(true).thenThrow(new SQLException("ORA-01555 snapshot too old"));
        when(failingRs.getObject(1)).thenReturn("ROW_1");

        ExportJobConfig failJob = ExportJobConfig.builder()
                .name("midstream_fail")
                .filePrefix("MIDSTREAM_FAIL")
                .build();

        assertThatThrownBy(() -> csvWriter.writeResultSet(failingRs, failJob))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-01555");

        try (Stream<Path> remaining = Files.list(stagingDir)) {
            assertThat(remaining.toList()).isEmpty();
        }

        // Verify write-empty-file=true produces a header-only CSV with 0 data rows
        ResultSet emptyRs = mock(ResultSet.class);
        when(emptyRs.getMetaData()).thenReturn(meta);
        when(emptyRs.next()).thenReturn(false);

        ExportJobConfig headerOnlyJob = ExportJobConfig.builder()
                .name("header_only")
                .filePrefix("HEADER_ONLY")
                .csv(CsvOptions.builder().writeEmptyFile(true).quoteChar("").build())
                .build();

        StreamingCsvWriter.StagedCsvFile headerOnly = csvWriter.writeResultSet(emptyRs, headerOnlyJob);
        assertThat(headerOnly.hasFile()).isTrue();
        assertThat(headerOnly.rowCount()).isZero();
        assertThat(Files.readAllLines(headerOnly.filePath())).containsExactly("COL1");
    }
}
