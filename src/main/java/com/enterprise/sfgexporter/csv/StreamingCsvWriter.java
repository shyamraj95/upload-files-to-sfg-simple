package com.enterprise.sfgexporter.csv;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.config.SecurityValidator;
import com.enterprise.sfgexporter.model.CsvOptions;
import com.enterprise.sfgexporter.model.ExportJobConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLXML;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * Streams JDBC {@link ResultSet} rows directly to disk using Apache Commons CSV
 * so heap memory remains flat O(1) regardless of dataset size.
 *
 * <p>Key design guarantees:</p>
 * <ul>
 *     <li>Single 64 KB output buffer and a reused per-row {@code Object[]} buffer minimize GC pressure.</li>
 *     <li>Writes to a temporary {@code .part} file in {@code staging/} and atomically promotes it to
 *         {@code .csv} upon completion; deletes {@code .part} on any error.</li>
 *     <li>Restricts POSIX permissions to {@code 0700} (staging dir) and {@code 0600} (staged CSV).</li>
 *     <li>Explicitly frees Oracle temporary {@link Clob} handles and never logs row or cell payloads.</li>
 * </ul>
 */
@Component
public class StreamingCsvWriter {

    private static final Logger log = LoggerFactory.getLogger(StreamingCsvWriter.class);
    private static final int WRITE_BUFFER_BYTES = 64 * 1024;
    private static final String LOCAL_PART_SUFFIX = ".part";
    private static final Set<PosixFilePermission> RESTRICTED_DIR_PERMS = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> RESTRICTED_FILE_PERMS = PosixFilePermissions.fromString("rw-------");

    private final Path stagingDir;
    private final Clock clock;

    @Autowired
    public StreamingCsvWriter(AppProperties properties) {
        this(Path.of(properties.getStorage().getStagingDir()), Clock.systemUTC());
    }

    public StreamingCsvWriter(Path stagingDir, Clock clock) {
        this.stagingDir = stagingDir.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /**
     * Streams all rows from {@code resultSet} into a timestamped CSV file inside the staging directory.
     * Writes to a local {@code .part} file first and atomically promotes it to {@code .csv} upon completion.
     */
    public StagedCsvFile writeResultSet(ResultSet resultSet, ExportJobConfig job) throws SQLException, IOException {
        SecurityValidator.validateIdentifier(job.getFilePrefix(), "filePrefix");
        ensureStagingDirectory();

        String finalFileName = buildTimestampedFileName(job);
        Path finalPath = SecurityValidator.ensureChildPath(stagingDir, stagingDir.resolve(finalFileName));
        String uniquePartName = finalFileName + "." + UUID.randomUUID().toString().substring(0, 8) + LOCAL_PART_SUFFIX;
        Path partPath = SecurityValidator.ensureChildPath(stagingDir, stagingDir.resolve(uniquePartName));

        CsvOptions csvOptions = job.getCsv();
        Charset charset = csvOptions.resolveCharset();
        CSVFormat format = buildCsvFormat(csvOptions);

        long rowCount = 0L;
        boolean finalized = false;
        try {
            try (OutputStream out = openRestrictedOutputStream(partPath);
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, charset), WRITE_BUFFER_BYTES);
                 CSVPrinter printer = new CSVPrinter(writer, format)) {

                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (csvOptions.isIncludeHeader() && columnCount > 0) {
                    String[] headers = new String[columnCount];
                    for (int i = 1; i <= columnCount; i++) {
                        headers[i - 1] = neutralizeFormulaIfNeeded(
                                metaData.getColumnLabel(i), csvOptions.isNeutralizeFormulas());
                    }
                    printer.printRecord((Object[]) headers);
                }

                // Reuse a single row array across iterations to minimize GC pressure on large exports
                Object[] rowBuffer = new Object[columnCount];
                while (resultSet.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        rowBuffer[i - 1] = normalizeColumnValue(resultSet.getObject(i), csvOptions);
                    }
                    printer.printRecord(rowBuffer);
                    rowCount++;
                }
                printer.flush();
            }

            if (rowCount == 0 && !csvOptions.isWriteEmptyFile()) {
                Files.deleteIfExists(partPath);
                finalized = true;
                log.info("Job '{}' produced 0 rows and write-empty-file=false; skipping CSV creation", job.getName());
                return new StagedCsvFile(null, null, 0L);
            }

            promotePartFile(partPath, finalPath);
            finalized = true;
            log.info("Generated staged CSV '{}' with {} row(s) for job '{}'", finalFileName, rowCount, job.getName());
            return new StagedCsvFile(finalPath, finalFileName, rowCount);

        } finally {
            if (!finalized) {
                deleteQuietly(partPath);
            }
        }
    }

    String buildTimestampedFileName(ExportJobConfig job) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(job.getTimestampPattern());
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneId.of(job.getTimeZone()));
        return job.getFilePrefix() + "_" + formatter.format(now) + ".csv";
    }

    private CSVFormat buildCsvFormat(CsvOptions options) {
        CSVFormat.Builder builder = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(options.getDelimiter().charAt(0))
                .setRecordSeparator(options.getRecordSeparator())
                .setNullString(options.getNullString());

        if (options.getQuoteChar() == null || options.getQuoteChar().isEmpty()) {
            builder.setQuote((Character) null);
        } else {
            builder.setQuote(options.getQuoteChar().charAt(0));
        }

        return builder.get();
    }

    private Object normalizeColumnValue(Object value, CsvOptions csvOptions) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            // Avoid scientific notation for financial amounts and numeric identifiers; negative numbers stay numeric
            return decimal.toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Temporal || value instanceof Date) {
            return value;
        }
        if (value instanceof Clob clob) {
            String text = readClobBounded(clob, csvOptions.getMaxClobChars());
            return neutralizeFormulaIfNeeded(text, csvOptions.isNeutralizeFormulas());
        }
        if (value instanceof SQLXML sqlxml) {
            String text = readSqlXmlBounded(sqlxml, csvOptions.getMaxClobChars());
            return neutralizeFormulaIfNeeded(text, csvOptions.isNeutralizeFormulas());
        }
        if (value instanceof Blob blob) {
            freeBlobQuietly(blob);
            throw new SQLException("Unsupported BLOB column in CSV export");
        }
        if (value instanceof String text) {
            return neutralizeFormulaIfNeeded(text, csvOptions.isNeutralizeFormulas());
        }
        return neutralizeFormulaIfNeeded(value.toString(), csvOptions.isNeutralizeFormulas());
    }

    private String readClobBounded(Clob clob, int maxClobChars) throws SQLException {
        try {
            long length = clob.length();
            if (length == 0L) {
                return "";
            }
            if (length > maxClobChars) {
                throw new SQLException("CLOB length (" + length + ") exceeds configured limit (" + maxClobChars + ")");
            }
            return clob.getSubString(1L, (int) length);
        } finally {
            freeClobQuietly(clob);
        }
    }

    private String readSqlXmlBounded(SQLXML sqlxml, int maxChars) throws SQLException {
        try (Reader reader = sqlxml.getCharacterStream()) {
            if (reader == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[Math.min(4096, Math.max(1, maxChars))];
            int read;
            while ((read = reader.read(buf)) != -1) {
                if (sb.length() + read > maxChars) {
                    throw new SQLException("SQLXML length exceeds configured limit (" + maxChars + ")");
                }
                sb.append(buf, 0, read);
            }
            return sb.toString();
        } catch (IOException ex) {
            throw new SQLException("Failed to read SQLXML column: " + ex.getMessage(), ex);
        } finally {
            freeSqlXmlQuietly(sqlxml);
        }
    }

    private String neutralizeFormulaIfNeeded(String text, boolean neutralizeFormulas) {
        if (!neutralizeFormulas || text == null || text.isEmpty()) {
            return text;
        }
        if (isFormulaTrigger(text.charAt(0))) {
            return "'" + text;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                if (c == '=' || c == '+' || c == '-' || c == '@') {
                    return "'" + text;
                }
                break;
            }
        }
        return text;
    }

    private boolean isFormulaTrigger(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' || c == '\n';
    }

    private OutputStream openRestrictedOutputStream(Path partPath) throws IOException {
        Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        SeekableByteChannel channel;
        try {
            channel = Files.newByteChannel(partPath, options, PosixFilePermissions.asFileAttribute(RESTRICTED_FILE_PERMS));
        } catch (UnsupportedOperationException ignored) {
            channel = Files.newByteChannel(partPath, options);
        }
        return Channels.newOutputStream(channel);
    }

    private void freeClobQuietly(Clob clob) {
        try {
            clob.free();
        } catch (SQLFeatureNotSupportedException ignored) {
            // Driver does not support explicit CLOB freeing
        } catch (SQLException ex) {
            log.debug("Failed to free JDBC CLOB cleanly: {}", ex.getMessage());
        }
    }

    private void freeSqlXmlQuietly(SQLXML sqlxml) {
        try {
            sqlxml.free();
        } catch (SQLFeatureNotSupportedException ignored) {
            // Driver does not support explicit SQLXML freeing
        } catch (SQLException ex) {
            log.debug("Failed to free JDBC SQLXML cleanly: {}", ex.getMessage());
        }
    }

    private void freeBlobQuietly(Blob blob) {
        try {
            blob.free();
        } catch (SQLFeatureNotSupportedException ignored) {
            // Driver does not support explicit BLOB freeing
        } catch (SQLException ex) {
            log.debug("Failed to free JDBC BLOB cleanly: {}", ex.getMessage());
        }
    }

    private void ensureStagingDirectory() throws IOException {
        if (Files.isSymbolicLink(stagingDir)) {
            throw new SecurityException(
                    "Refusing to use symbolic link as staging directory: " + stagingDir.getFileName());
        }
        boolean existed = Files.exists(stagingDir, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(stagingDir);
        if (!existed) {
            try {
                Files.setPosixFilePermissions(stagingDir, RESTRICTED_DIR_PERMS);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem
            }
        }
    }

    private void promotePartFile(Path partPath, Path finalPath) throws IOException {
        if (Files.isSymbolicLink(finalPath)) {
            throw new SecurityException(
                    "Refusing to overwrite symbolic link in staging directory: " + finalPath.getFileName());
        }
        try {
            Files.move(partPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(partPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupEx) {
            log.warn("Failed to remove partial staging file '{}': {}", path.getFileName(), cleanupEx.getMessage());
        }
    }

    public record StagedCsvFile(Path filePath, String fileName, long rowCount) {
        public boolean hasFile() {
            return filePath != null;
        }
    }
}
