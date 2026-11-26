package com.enterprise.sfgexporter.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Component;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.config.SecurityValidator;
import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.model.ExportJobConfig;

/**
 * Executes configured Oracle PL/SQL stored procedures ({@code SYS_REFCURSOR})
 * or parameterized
 * SQL query files and streams the {@link ResultSet} directly into
 * {@link StreamingCsvWriter}.
 *
 * <p>
 * Uses {@link JdbcTemplate execute(CallableStatementCreator,
 * CallableStatementCallback)} and
 * {@link NamedParameterJdbcTemplate query(String,
 * org.springframework.jdbc.core.namedparam.SqlParameterSource,
 * org.springframework.jdbc.core.ResultSetExtractor)}
 * rather than collecting rows into memory, keeping memory consumption constant
 * even for large extracts.
 * </p>
 */
@Component
public class OracleDataExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleDataExtractor.class);
    private static final long MAX_SQL_FILE_BYTES = 1024L * 1024L;

    private final ResourceLoader resourceLoader;
    private final StreamingCsvWriter csvWriter;
    private final String allowedSqlDir;
    private final JdbcTemplate jdbcTemplate;
    private final StreamingNamedParameterJdbcTemplate namedJdbcTemplate;

    @Autowired
    public OracleDataExtractor(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            StreamingCsvWriter csvWriter,
            AppProperties properties) {
        this(dataSource, resourceLoader, csvWriter, properties.storage().sqlDir());
    }

    OracleDataExtractor(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            StreamingCsvWriter csvWriter) {
        this(dataSource, resourceLoader, csvWriter, "");
    }

    private OracleDataExtractor(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            StreamingCsvWriter csvWriter,
            String allowedSqlDir) {
        this.resourceLoader = resourceLoader;
        this.csvWriter = csvWriter;
        this.allowedSqlDir = allowedSqlDir != null ? allowedSqlDir : "";
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.namedJdbcTemplate = new StreamingNamedParameterJdbcTemplate(this.jdbcTemplate);
    }

    /**
     * Dispatches extraction based on {@link ExportJobConfig#sourceType()} and
     * streams the resulting
     * JDBC {@link ResultSet} directly into a staged CSV file.
     *
     * @param job validated export job configuration
     * @return metadata pointing to the staged CSV file (or an empty descriptor when
     *         skipped)
     */
    public StreamingCsvWriter.StagedCsvFile extractToCsv(ExportJobConfig job) {
        return switch (job.sourceType()) {
            case STORED_PROCEDURE -> extractFromStoredProcedure(job);
            case SQL_QUERY -> extractFromSqlFile(job);
            case null -> throw new IllegalArgumentException("Unsupported source type: null");
        };
    }

    /**
     * Invokes an Oracle PL/SQL stored procedure returning a {@code SYS_REFCURSOR}
     * OUT parameter.
     *
     * <p>
     * Uses
     * {@link JdbcTemplate#execute(CallableStatementCreator, CallableStatementCallback)}
     * instead of
     * {@code SimpleJdbcCall.returningResultSet(...)} because {@code SimpleJdbcCall}
     * materializes every
     * row into an in-memory {@code List}, whereas this implementation streams the
     * cursor directly to disk.
     * </p>
     */
    private StreamingCsvWriter.StagedCsvFile extractFromStoredProcedure(ExportJobConfig job) {
        String callSql = buildSafeProcedureCallSql(job);
        // Snapshot map entries in declaration order so positional JDBC bindings align
        // with named PL/SQL args
        List<Map.Entry<String, Object>> inParams = new ArrayList<>(job.parameters().entrySet());
        int cursorParamIndex = inParams.size() + 1;

        log.info("Executing Oracle PL/SQL stored procedure for job '{}' (fetchSize={}, timeout={}s)",
                job.name(), job.fetchSize(), job.queryTimeoutSeconds());

        CallableStatementCreator creator = connection -> {
            // Forward-only, read-only cursor avoids scrollable buffer overhead inside
            // Oracle JDBC
            CallableStatement cs = connection.prepareCall(
                    callSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                cs.setFetchSize(job.fetchSize());
                cs.setQueryTimeout(job.queryTimeoutSeconds());

                for (int i = 0; i < inParams.size(); i++) {
                    cs.setObject(i + 1, inParams.get(i).getValue());
                }
                // Standard JDBC 4.2 Types.REF_CURSOR is natively supported by ojdbc11 for
                // Oracle SYS_REFCURSOR
                cs.registerOutParameter(cursorParamIndex, Types.REF_CURSOR);
                return cs;
            } catch (SQLException | RuntimeException ex) {
                // Close statement immediately if binding throws before JdbcTemplate can take
                // ownership
                JdbcUtils.closeStatement(cs);
                throw ex;
            }
        };

        CallableStatementCallback<StreamingCsvWriter.StagedCsvFile> callback = cs -> {
            cs.execute();
            try (ResultSet rs = cs.getObject(cursorParamIndex, ResultSet.class)) {
                if (rs == null) {
                    throw new SQLException(
                            "Stored procedure returned a null SYS_REFCURSOR for job '" + job.name() + "'");
                }
                // Apply fetchSize explicitly on the returned cursor so ojdbc11 fetches in
                // chunks, not 10 rows at a time
                rs.setFetchSize(job.fetchSize());
                return writeResultSetUnchecked(rs, job);
            }
        };

        return Objects.requireNonNull(
                jdbcTemplate.execute(creator, callback),
                "Stored procedure extraction returned null result for job '" + job.name() + "'");
    }

    /**
     * Executes an external parameterized {@code .sql} file and streams the
     * {@link ResultSet}
     * row-by-row via {@link NamedParameterJdbcTemplate}'s
     * {@code ResultSetExtractor}.
     */
    private StreamingCsvWriter.StagedCsvFile extractFromSqlFile(ExportJobConfig job) {
        SecurityValidator.validateSqlFileLocation(job.sqlFile(), "sqlFile");
        String sql = loadSqlResource(job.sqlFile());

        log.info("Executing parameterized SQL file '{}' for job '{}' (fetchSize={}, timeout={}s)",
                job.sqlFile(), job.name(), job.fetchSize(), job.queryTimeoutSeconds());

        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : job.parameters().entrySet()) {
            // Re-validate parameter keys as defense-in-depth before binding
            SecurityValidator.validateIdentifier(entry.getKey(), "sql parameter name");
            paramSource.addValue(entry.getKey(), entry.getValue());
        }

        return Objects.requireNonNull(
                namedJdbcTemplate.queryWithStatementConfig(
                        sql,
                        paramSource,
                        job.fetchSize(),
                        job.queryTimeoutSeconds(),
                        rs -> {
                            rs.setFetchSize(job.fetchSize());
                            return writeResultSetUnchecked(rs, job);
                        }),
                "SQL extraction returned null result for job '" + job.name() + "'");
    }

    /**
     * Builds a strictly allowlist-validated Oracle PL/SQL named-argument procedure
     * call, e.g.:
     * {@code {call FIN_SCHEMA.PKG_GL_EXPORT.SP_DAILY_JOURNALS(P_DAYS_BACK => ?, P_RESULT_CURSOR => ?)}}
     */
    String buildSafeProcedureCallSql(ExportJobConfig job) {
        StringBuilder target = new StringBuilder();
        if (job.schemaName() != null && !job.schemaName().isBlank()) {
            SecurityValidator.validateIdentifier(job.schemaName(), "schemaName");
            target.append(job.schemaName()).append('.');
        }
        if (job.packageName() != null && !job.packageName().isBlank()) {
            SecurityValidator.validateIdentifier(job.packageName(), "packageName");
            target.append(job.packageName()).append('.');
        }
        SecurityValidator.validateIdentifier(job.procedureName(), "procedureName");
        target.append(job.procedureName());

        SecurityValidator.validateIdentifier(job.cursorParamName(), "cursorParamName");

        List<String> args = new ArrayList<>();
        for (String paramName : job.parameters().keySet()) {
            SecurityValidator.validateIdentifier(paramName, "procedure parameter name");
            args.add(paramName + " => ?");
        }
        args.add(job.cursorParamName() + " => ?");

        return "{call " + target + "(" + String.join(", ", args) + ")}";
    }

    private String loadSqlResource(String location) {
        try {
            String rawSql;
            if (location.startsWith("file:")) {
                Path verifiedSqlPath = SecurityValidator.validateExternalSqlFileSecurity(location, allowedSqlDir);
                long fileSize = Files.size(verifiedSqlPath);
                if (fileSize > MAX_SQL_FILE_BYTES) {
                    throw new IllegalStateException(
                            "SQL resource exceeds maximum allowed size (" + MAX_SQL_FILE_BYTES + " bytes): "
                                    + location);
                }
                rawSql = Files.readString(verifiedSqlPath, StandardCharsets.UTF_8);
            } else {
                Resource resource = resourceLoader.getResource(location);
                if (!resource.exists()) {
                    throw new IllegalStateException("SQL resource not found: " + location);
                }
                try (InputStream in = resource.getInputStream()) {
                    byte[] bytes = in.readNBytes((int) MAX_SQL_FILE_BYTES + 1);
                    if (bytes.length > MAX_SQL_FILE_BYTES) {
                        throw new IllegalStateException(
                                "SQL resource exceeds maximum allowed size (" + MAX_SQL_FILE_BYTES + " bytes): "
                                        + location);
                    }
                    rawSql = new String(bytes, StandardCharsets.UTF_8);
                }
            }

            String sql = rawSql.trim();
            if (sql.isEmpty()) {
                throw new IllegalStateException("SQL resource is empty: " + location);
            }
            // Strip a single trailing semicolon if present (Oracle JDBC rejects trailing
            // ';' in SQL queries)
            if (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }
            return sql;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read SQL resource: " + location, ex);
        }
    }

    private StreamingCsvWriter.StagedCsvFile writeResultSetUnchecked(ResultSet rs, ExportJobConfig job)
            throws SQLException {
        try {
            return csvWriter.writeResultSet(rs, job);
        } catch (IOException ex) {
            throw new DataRetrievalFailureException(
                    "Failed while streaming JDBC ResultSet to CSV for job '" + job.name() + "'", ex);
        }
    }

    /**
     * Thread-safe subclass of {@link NamedParameterJdbcTemplate} allowing
     * per-execution
     * {@code fetchSize} and {@code queryTimeout} without allocating a new template
     * per job.
     */
    private static final class StreamingNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {
        StreamingNamedParameterJdbcTemplate(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        <T> T queryWithStatementConfig(
                String sql,
                SqlParameterSource paramSource,
                int fetchSize,
                int queryTimeoutSeconds,
                ResultSetExtractor<T> rse) {
            PreparedStatementCreator delegate = getPreparedStatementCreator(sql, paramSource);
            PreparedStatementCreator configured = connection -> {
                PreparedStatement ps = delegate.createPreparedStatement(connection);
                try {
                    ps.setFetchSize(fetchSize);
                    ps.setQueryTimeout(queryTimeoutSeconds);
                    return ps;
                } catch (SQLException | RuntimeException ex) {
                    JdbcUtils.closeStatement(ps);
                    throw ex;
                }
            };
            return getJdbcOperations().query(configured, rse);
        }
    }
}
