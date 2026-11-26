package com.enterprise.sfgexporter.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Per-job CSV formatting options consumed by {@code StreamingCsvWriter}.
 */
public class CsvOptions {

    /** Single-character field delimiter (e.g., {@code ,}, {@code |}, or {@code \t}). */
    private String delimiter = ",";

    /** Whether to emit JDBC column labels as the first line of the CSV file. */
    private boolean includeHeader = true;

    /** Single-character quote symbol (leave empty/null to disable quoting). */
    private String quoteChar = "\"";

    /** Line ending sequence written after each CSV record (e.g., {@code \n} or {@code \r\n}). */
    private String recordSeparator = "\n";

    /** Literal string emitted for SQL {@code NULL} column values (default: empty string). */
    private String nullString = "";

    /** Output file character encoding (default: {@code UTF-8}). */
    private String charset = StandardCharsets.UTF_8.name();

    /**
     * When {@code true}, a 0-row extract still writes a header-only (or empty) CSV and uploads it to SFG.
     * When {@code false}, 0-row extracts skip file creation and SFTP upload.
     */
    private boolean writeEmptyFile = true;

    /**
     * Prefixes non-numeric text/CLOB cells starting with spreadsheet formula triggers
     * ({@code =}, {@code +}, {@code -}, {@code @}, {@code \t}, {@code \r}) with {@code '} to prevent
     * CSV formula injection in Excel/Calc (negative numeric {@code BigDecimal} values are never altered).
     */
    private boolean neutralizeFormulas = true;

    /** Maximum permitted character length for a single Oracle {@code CLOB} cell to bound heap usage (default: 1,048,576). */
    private int maxClobChars = 1_048_576;

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public boolean isIncludeHeader() {
        return includeHeader;
    }

    public void setIncludeHeader(boolean includeHeader) {
        this.includeHeader = includeHeader;
    }

    public String getQuoteChar() {
        return quoteChar;
    }

    public void setQuoteChar(String quoteChar) {
        this.quoteChar = quoteChar;
    }

    public String getRecordSeparator() {
        return recordSeparator;
    }

    public void setRecordSeparator(String recordSeparator) {
        this.recordSeparator = recordSeparator;
    }

    public String getNullString() {
        return nullString;
    }

    public void setNullString(String nullString) {
        this.nullString = nullString;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public Charset resolveCharset() {
        return Charset.forName(charset);
    }

    public boolean isWriteEmptyFile() {
        return writeEmptyFile;
    }

    public void setWriteEmptyFile(boolean writeEmptyFile) {
        this.writeEmptyFile = writeEmptyFile;
    }

    public boolean isNeutralizeFormulas() {
        return neutralizeFormulas;
    }

    public void setNeutralizeFormulas(boolean neutralizeFormulas) {
        this.neutralizeFormulas = neutralizeFormulas;
    }

    public int getMaxClobChars() {
        return maxClobChars;
    }

    public void setMaxClobChars(int maxClobChars) {
        this.maxClobChars = maxClobChars;
    }
}
