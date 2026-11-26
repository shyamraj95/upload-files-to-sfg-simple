package com.enterprise.sfgexporter.db;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.config.SecurityValidator;
import com.enterprise.sfgexporter.csv.StreamingCsvWriter;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import com.enterprise.sfgexporter.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.CallableStatementCreator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes configured Oracle PL/SQL stored procedures ({@code SYS_REFCURSOR}) or parameterized
 * SQL query files and streams the {@link ResultSet} directly into {@link StreamingCsvWriter}.
 *
 * <p>Uses {@link JdbcTemplate execute(CallableStatementCreator, CallableStatementCallback)} and
 * {@link NamedParameterJdbcTemplate query(String, org.springframework.jdbc.core.namedparam.SqlParameterSource, org.springframework.jdbc.core.ResultSetExtractor)}
 * rather than collecting rows into memory, keeping memory consumption constant even for large extracts.</p>
 */
@Component
public class OracleDataExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleDataExtractor.class);

    private final DataSource dataSource;
    private final ResourceLoader resourceLoader;
    private final StreamingCsvWriter csvWriter;
    private final String allowedSqlDir;
    private final Map<String, NamedParameterJdbcTemplate> namedTemplates = new ConcurrentHashMap<>();

    @Autowired
    public OracleDataExtractor(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            StreamingCsvWriter csvWriter,
            AppProperties properties
    ) {
        this(dataSource, resourceLoader, csvWriter, properties.getStorage().getSqlDir());
    }

    OracleDataExtractor(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            StreamingCsvWriter csvWriter
    ) {
        this(dataSource, resourceLoader, csvWriter, "");
    }

    private OracleDataExtractor(
            DataSource dataSource,
            ResourceLoader resourceLoader,
            StreamingCsvWriter csvWriter,
            String allowedSqlDir
    ) {
        this.dataSource = dataSource;
        this.resourceLoader = resourceLoader;
        this.csvWriter = csvWriter;
        this.allowedSqlDir = allowedSqlDir != null ? allowedSqlDir : "";
    }

    /**
     * Dispatches extraction based on {@link ExportJobConfig#getSourceType()} and streams the resulting
     * JDBC {@link ResultSet} directly into a staged CSV file.
     *
     * @param job validated export job configuration
     * @return metadata pointing to the staged CSV file (or an empty descriptor when skipped)
     */
    public StreamingCsvWriter.StagedCsvFile extractToCsv(ExportJobConfig job) {
        if (job.getSourceType() == SourceType.STORED_PROCEDURE) {
            return extractFromStoredProcedure(job);
        }
        if (job.getSourceType() == SourceType.SQL_QUERY) {
            return extractFromSqlFile(job);
        }
        throw new IllegalArgumentException("Unsupported source type: " + job.getSourceType());
    }

    /**
     * Invokes an Oracle PL/SQL stored procedure returning a {@code SYS_REFCURSOR} OUT parameter.
     *
     * <p>Uses {@link JdbcTemplate#execute(CallableStatementCreator, CallableStatementCallback)} instead of
     * {@code SimpleJdbcCall.returningResultSet(...)} because {@code SimpleJdbcCall} materializes every
     * row into an in-memory {@code List}, whereas this implementation streams the cursor directly to disk.</p>
     */
    private StreamingCsvWriter.StagedCsvFile extractFromStoredProcedure(ExportJobConfig job) {
        String callSql = buildSafeProcedureCallSql(job);
        // Snapshot map entries in declaration order so positional JDBC bindings align with named PL/SQL args
        List<Map.Entry<String, Object>> inParams = new ArrayList<>(job.getParameters().entrySet());
        int cursorParamIndex = inParams.size() + 1;

        log.info("Executing Oracle PL/SQL stored procedure for job '{}' (fetchSize={}, timeout={}s)",
                job.getName(), job.getFetchSize(), job.getQueryTimeoutSeconds());

        JdbcTemplate jobJdbcTemplate = createJobJdbcTemplate(job);

        CallableStatementCreator creator = connection -> {
            // Forward-only, read-only cursor avoids scrollable buffer overhead inside Oracle JDBC
            CallableStatement cs = connection.prepareCall(
                    callSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                cs.setFetchSize(job.getFetchSize());
                cs.setQueryTimeout(job.getQueryTimeoutSeconds());

                for (int i = 0; i < inParams.size(); i++) {
                    cs.setObject(i + 1, inParams.get(i).getValue());
                }
                // Standard JDBC 4.2 Types.REF_CURSOR is natively supported by ojdbc11 for Oracle SYS_REFCURSOR
                cs.registerOutParameter(cursorParamIndex, Types.REF_CURSOR);
                return cs;
            } catch (SQLException | RuntimeException ex) {
                // Close statement immediately if binding throws before JdbcTemplate can take ownership
                JdbcUtils.closeStatement(cs);
                throw ex;
            }
        };

        CallableStatementCallback<StreamingCsvWriter.StagedCsvFile> callback = cs -> {
            cs.execute();
            try (ResultSet rs = cs.getObject(cursorParamIndex, ResultSet.class)) {
                if (rs == null) {
                    throw new SQLException("Stored procedure returned a null SYS_REFCURSOR for job '" + job.getName() + "'");
                }
                // Apply fetchSize explicitly on the returned cursor so ojdbc11 fetches in chunks, not 10 rows at a time
                rs.setFetchSize(job.getFetchSize());
                return writeResultSetUnchecked(rs, job);
            }
        };

        return Objects.requireNonNull(
                jobJdbcTemplate.execute(creator, callback),
                "Stored procedure extraction returned null result for job '" + job.getName() + "'");
    }

    /**
     * Executes an external parameterized {@code .sql} file and streams the {@link ResultSet}
     * row-by-row via {@link NamedParameterJdbcTemplate}'s {@code ResultSetExtractor}.
     */
    private StreamingCsvWriter.StagedCsvFile extractFromSqlFile(ExportJobConfig job) {
        SecurityValidator.validateSqlFileLocation(job.getSqlFile(), "sqlFile");
        String sql = loadSqlResource(job.getSqlFile());

        log.info("Executing parameterized SQL file '{}' for job '{}' (fetchSize={}, timeout={}s)",
                job.getSqlFile(), job.getName(), job.getFetchSize(), job.getQueryTimeoutSeconds());

        NamedParameterJdbcTemplate namedJdbc = namedTemplates.computeIfAbsent(
                job.getName(), ignored -> new NamedParameterJdbcTemplate(createJobJdbcTemplate(job)));

        MapSqlParameterSource paramSource = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : job.getParameters().entrySet()) {
            // Re-validate parameter keys as defense-in-depth before binding
            SecurityValidator.validateIdentifier(entry.getKey(), "sql parameter name");
            paramSource.addValue(entry.getKey(), entry.getValue());
        }

        return Objects.requireNonNull(
                namedJdbc.query(sql, paramSource, rs -> {
                    rs.setFetchSize(job.getFetchSize());
                    return writeResultSetUnchecked(rs, job);
                }),
                "SQL extraction returned null result for job '" + job.getName() + "'");
    }

    /**
     * Builds a strictly allowlist-validated Oracle PL/SQL named-argument procedure call, e.g.:
     * {@code {call FIN_SCHEMA.PKG_GL_EXPORT.SP_DAILY_JOURNALS(P_DAYS_BACK => ?, P_RESULT_CURSOR => ?)}}
     */
    String buildSafeProcedureCallSql(ExportJobConfig job) {
        StringBuilder target = new StringBuilder();
        if (job.getSchemaName() != null && !job.getSchemaName().isBlank()) {
            SecurityValidator.validateIdentifier(job.getSchemaName(), "schemaName");
            target.append(job.getSchemaName()).append('.');
        }
        if (job.getPackageName() != null && !job.getPackageName().isBlank()) {
            SecurityValidator.validateIdentifier(job.getPackageName(), "packageName");
            target.append(job.getPackageName()).append('.');
        }
        SecurityValidator.validateIdentifier(job.getProcedureName(), "procedureName");
        target.append(job.getProcedureName());

        SecurityValidator.validateIdentifier(job.getCursorParamName(), "cursorParamName");

        List<String> args = new ArrayList<>();
        for (String paramName : job.getParameters().keySet()) {
            SecurityValidator.validateIdentifier(paramName, "procedure parameter name");
            args.add(paramName + " => ?");
        }
        args.add(job.getCursorParamName() + " => ?");

        return "{call " + target + "(" + String.join(", ", args) + ")}";
    }

    /**
     * Creates a lightweight, per-execution {@link JdbcTemplate} sharing the pooled {@link DataSource}
     * so concurrent jobs with different {@code fetchSize} or {@code queryTimeoutSeconds} never mutate
     * a shared singleton template.
     */
    private JdbcTemplate createJobJdbcTemplate(ExportJobConfig job) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setFetchSize(job.getFetchSize());
        jdbcTemplate.setQueryTimeout(job.getQueryTimeoutSeconds());
        return jdbcTemplate;
    }

    private String loadSqlResource(String location) {
        if (location.startsWith("file:")) {
            SecurityValidator.validateExternalSqlFileSecurity(location, allowedSqlDir);
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("SQL resource not found: " + location);
        }
        try {
            String sql = resource.getContentAsString(StandardCharsets.UTF_8).trim();
            if (sql.isEmpty()) {
                throw new IllegalStateException("SQL resource is empty: " + location);
            }
            // Strip a single trailing semicolon if present (Oracle JDBC rejects trailing ';' in SQL queries)
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
                    "Failed while streaming JDBC ResultSet to CSV for job '" + job.getName() + "'", ex);
        }
    }
}
