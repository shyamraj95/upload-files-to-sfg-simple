package com.enterprise.sfgexporter.model;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Per-job CSV formatting options consumed by {@code StreamingCsvWriter}.
 *
 * @param delimiter          Single-character field delimiter (e.g., {@code ,}, {@code |}, or {@code \t}).
 * @param includeHeader      Whether to emit JDBC column labels as the first line of the CSV file.
 * @param quoteChar          Single-character quote symbol (leave empty/null to disable quoting).
 * @param recordSeparator    Line ending sequence written after each CSV record (e.g., {@code \n} or {@code \r\n}).
 * @param nullString         Literal string emitted for SQL {@code NULL} column values (default: empty string).
 * @param charset            Output file character encoding (default: {@code UTF-8}).
 * @param writeEmptyFile     When {@code true}, a 0-row extract still writes a header-only (or empty) CSV and uploads it to SFG.
 * @param neutralizeFormulas Prefixes non-numeric text/CLOB/SQLXML cells starting with spreadsheet formula triggers with {@code '}.
 * @param maxClobChars       Maximum permitted character length for a single Oracle {@code CLOB}/{@code SQLXML} cell.
 */
public record CsvOptions(
        @DefaultValue(",") String delimiter,
        @DefaultValue("true") boolean includeHeader,
        @DefaultValue("\"") String quoteChar,
        @DefaultValue("\n") String recordSeparator,
        @DefaultValue("") String nullString,
        @DefaultValue("UTF-8") String charset,
        @DefaultValue("true") boolean writeEmptyFile,
        @DefaultValue("true") boolean neutralizeFormulas,
        @DefaultValue("1048576") int maxClobChars
) {

    public Charset resolveCharset() {
        return Charset.forName(charset);
    }

    public static CsvOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .delimiter(delimiter)
                .includeHeader(includeHeader)
                .quoteChar(quoteChar)
                .recordSeparator(recordSeparator)
                .nullString(nullString)
                .charset(charset)
                .writeEmptyFile(writeEmptyFile)
                .neutralizeFormulas(neutralizeFormulas)
                .maxClobChars(maxClobChars);
    }

    public static final class Builder {
        private String delimiter = ",";
        private boolean includeHeader = true;
        private String quoteChar = "\"";
        private String recordSeparator = "\n";
        private String nullString = "";
        private String charset = StandardCharsets.UTF_8.name();
        private boolean writeEmptyFile = true;
        private boolean neutralizeFormulas = true;
        private int maxClobChars = 1_048_576;

        public Builder delimiter(String delimiter) {
            this.delimiter = delimiter;
            return this;
        }

        public Builder includeHeader(boolean includeHeader) {
            this.includeHeader = includeHeader;
            return this;
        }

        public Builder quoteChar(String quoteChar) {
            this.quoteChar = quoteChar;
            return this;
        }

        public Builder recordSeparator(String recordSeparator) {
            this.recordSeparator = recordSeparator;
            return this;
        }

        public Builder nullString(String nullString) {
            this.nullString = nullString;
            return this;
        }

        public Builder charset(String charset) {
            this.charset = charset;
            return this;
        }

        public Builder writeEmptyFile(boolean writeEmptyFile) {
            this.writeEmptyFile = writeEmptyFile;
            return this;
        }

        public Builder neutralizeFormulas(boolean neutralizeFormulas) {
            this.neutralizeFormulas = neutralizeFormulas;
            return this;
        }

        public Builder maxClobChars(int maxClobChars) {
            this.maxClobChars = maxClobChars;
            return this;
        }

        public CsvOptions build() {
            return new CsvOptions(
                    delimiter,
                    includeHeader,
                    quoteChar,
                    recordSeparator,
                    nullString,
                    charset,
                    writeEmptyFile,
                    neutralizeFormulas,
                    maxClobChars
            );
        }
    }
}
