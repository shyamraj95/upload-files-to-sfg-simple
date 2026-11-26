package com.enterprise.sfgexporter.sftp;

import com.enterprise.sfgexporter.config.SecurityValidator;
import com.enterprise.sfgexporter.model.RetryConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.apache.sshd.common.SshException;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Uploads CSV files to IBM Sterling File Gateway (SFG) atomically with resilient retry handling:
 * <ol>
 *     <li>Streams the local file via a 64 KB {@link BufferedInputStream} to
 *         {@code <remoteDir>/<filename><tempSuffix>} (e.g., {@code .tmp}).</li>
 *     <li>Atomically renames {@code .tmp} to {@code .csv} so SFG mailbox routing rules never pick up
 *         an incomplete file.</li>
 *     <li>Best-effort deletes any partial remote {@code .tmp} file if either write or rename fails.</li>
 *     <li>Retries transient network/I/O failures with capped, jittered (+/-15%) exponential backoff,
 *         while failing fast on host-key mismatches or authentication errors to avoid SFG account lockouts.</li>
 * </ol>
 */
@Component
public class AtomicSftpUploader {

    private static final Logger log = LoggerFactory.getLogger(AtomicSftpUploader.class);
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;
    // SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE (9), AUTH_CANCELLED_BY_USER (13), NO_MORE_AUTH_METHODS (14)
    private static final Set<Integer> NON_RETRYABLE_DISCONNECT_CODES = Set.of(9, 13, 14);
    private static final List<String> NON_RETRYABLE_MESSAGE_MARKERS = List.of(
            "server key did not validate",
            "host key",
            "no more authentication methods",
            "authentication failed"
    );

    private final SftpSessionFactoryRegistry sessionFactoryRegistry;
    private final Map<String, Semaphore> targetBulkheads = new ConcurrentHashMap<>();

    public AtomicSftpUploader(SftpSessionFactoryRegistry sessionFactoryRegistry) {
        this.sessionFactoryRegistry = sessionFactoryRegistry;
    }

    /**
     * Atomically uploads {@code localFile} to the configured SFG target and returns the final remote path.
     */
    public String uploadAtomically(Path localFile, String targetKey, String jobRemoteDir) {
        if (Files.isSymbolicLink(localFile) || !Files.isRegularFile(localFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Local CSV file does not exist or is a symbolic link: " + localFile.getFileName());
        }

        SftpTargetConfig targetConfig = sessionFactoryRegistry.getTargetConfig(targetKey);
        SessionFactory<SftpClient.DirEntry> sessionFactory = sessionFactoryRegistry.getSessionFactory(targetKey);

        String fileName = Objects.requireNonNull(
                localFile.getFileName(), "Local file path must include a file name").toString();
        String finalRemotePath = buildRemotePath(targetConfig.remoteBaseDir(), jobRemoteDir, fileName);
        String tempRemotePath = finalRemotePath + targetConfig.tempSuffix();

        Semaphore bulkhead = targetBulkheads.computeIfAbsent(
                targetKey, ignored -> new Semaphore(Math.max(1, targetConfig.maxConcurrentUploads())));
        TransferPaths paths = new TransferPaths(localFile, fileName, tempRemotePath, finalRemotePath);
        return executeWithRetry(bulkhead, sessionFactory, targetKey, targetConfig, paths);
    }

    private String executeWithRetry(
            Semaphore bulkhead,
            SessionFactory<SftpClient.DirEntry> sessionFactory,
            String targetKey,
            SftpTargetConfig targetConfig,
            TransferPaths paths
    ) {
        RetryConfig retry = targetConfig.retry();
        int maxAttempts = Math.max(1, retry.maxAttempts());
        long currentBackoffMs = retry.initialBackoffMs();

        Exception lastException = null;
        int attemptsExecuted = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attemptsExecuted = attempt;
            acquireBulkhead(bulkhead, targetKey, targetConfig.connectTimeoutMs());
            try {
                executeTransferWithBulkhead(bulkhead, sessionFactory, paths);
                log.info("Atomically uploaded '{}' to SFG target '{}' at '{}' (attempt {}/{})",
                        paths.fileName(), targetKey, paths.finalRemotePath(), attempt, maxAttempts);
                return paths.finalRemotePath();
            } catch (IOException | RuntimeException ex) {
                lastException = ex;
                if (isNonRetryable(ex) || attempt == maxAttempts) {
                    log.error("SFTP upload failed permanently for '{}' to target '{}' on attempt {}/{}: {}",
                            paths.fileName(), targetKey, attempt, maxAttempts, summarizeException(ex));
                    break;
                }

                long sleepMs = computeJitteredBackoff(currentBackoffMs, retry.maxBackoffMs());
                log.warn("Transient SFTP upload failure for '{}' to target '{}' (attempt {}/{}): {}. Retrying in {} ms",
                        paths.fileName(), targetKey, attempt, maxAttempts, summarizeException(ex), sleepMs);
                sleepUninterruptibly(sleepMs);
                currentBackoffMs = Math.min(
                        retry.maxBackoffMs(),
                        Math.round(currentBackoffMs * retry.multiplier()));
            }
        }

        throw new SftpUploadException(
                "Failed to upload '" + paths.fileName() + "' to SFTP target '" + targetKey + "' after " + attemptsExecuted + " attempt(s)",
                lastException);
    }

    private void executeTransferWithBulkhead(
            Semaphore bulkhead,
            SessionFactory<SftpClient.DirEntry> sessionFactory,
            TransferPaths paths
    ) throws IOException {
        try {
            executeAtomicTransfer(sessionFactory, paths);
        } finally {
            // Release concurrency permit immediately after transfer attempt, before retry backoff sleep
            bulkhead.release();
        }
    }

    private void acquireBulkhead(Semaphore bulkhead, String targetKey, long timeoutMs) {
        try {
            if (!bulkhead.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new SftpUploadException("SFTP target '" + targetKey + "' concurrency bulkhead saturated", null);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SftpUploadException("Interrupted waiting for SFTP target '" + targetKey + "' slot", ex);
        }
    }

    private void executeAtomicTransfer(
            SessionFactory<SftpClient.DirEntry> sessionFactory,
            TransferPaths paths
    ) throws IOException {
        try (Session<SftpClient.DirEntry> session = sessionFactory.getSession()) {
            streamAndRenameWithCleanup(session, paths);
        }
    }

    private void streamAndRenameWithCleanup(
            Session<SftpClient.DirEntry> session,
            TransferPaths paths
    ) throws IOException {
        try (InputStream inputStream = new BufferedInputStream(
                Files.newInputStream(paths.localFile(), LinkOption.NOFOLLOW_LINKS), STREAM_BUFFER_BYTES)) {
            session.write(inputStream, paths.tempRemotePath());
            session.rename(paths.tempRemotePath(), paths.finalRemotePath());
        } catch (IOException ex) {
            // Always attempt cleanup of partial/orphan .tmp file (even if write() failed mid-stream)
            cleanupRemoteTempFile(session, paths.tempRemotePath());
            throw ex;
        } catch (RuntimeException ex) {
            cleanupRemoteTempFile(session, paths.tempRemotePath());
            throw new IOException("Atomic SFTP transfer error: " + ex.getMessage(), ex);
        }
    }

    private void cleanupRemoteTempFile(Session<SftpClient.DirEntry> session, String tempRemotePath) {
        if (!session.isOpen()) {
            log.debug("Skipping remote temp cleanup for '{}': SFTP session is already closed", tempRemotePath);
            return;
        }
        try {
            if (session.exists(tempRemotePath)) {
                session.remove(tempRemotePath);
                log.debug("Cleaned up incomplete remote temp file '{}'", tempRemotePath);
            }
        } catch (IOException | RuntimeException cleanupEx) {
            log.warn("Unable to remove incomplete remote temp file '{}': {}",
                    tempRemotePath, summarizeException(cleanupEx));
        }
    }

    String buildRemotePath(String baseDir, String jobDir, String fileName) {
        SecurityValidator.validateRemotePath(baseDir, "remoteBaseDir");
        SecurityValidator.validateRemotePath(jobDir, "remoteDir");
        SecurityValidator.validateSafeFileName(fileName, "fileName");

        StringBuilder path = new StringBuilder();
        appendPathSegment(path, baseDir);
        appendPathSegment(path, jobDir);
        appendPathSegment(path, fileName);

        String combined = path.toString();
        boolean leadingSlash = (baseDir != null && baseDir.startsWith("/"))
                || ((baseDir == null || baseDir.isBlank()) && jobDir != null && jobDir.startsWith("/"));
        return leadingSlash ? "/" + combined : combined;
    }

    private void appendPathSegment(StringBuilder builder, String rawSegment) {
        if (rawSegment == null || rawSegment.isBlank()) {
            return;
        }
        String trimmed = trimSlashes(rawSegment);
        if (trimmed.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('/');
        }
        builder.append(trimmed);
    }

    private String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Fails fast on host-key mismatches, authentication failures, or invalid configuration
     * rather than retrying and risking SFG account lockout.
     */
    boolean isNonRetryable(Throwable throwable) {
        int depth = 0;
        for (Throwable current = throwable; current != null && depth++ < 32; current = current.getCause()) {
            if (isPermanentFailure(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private boolean isPermanentFailure(Throwable cause) {
        if (cause instanceof SecurityException
                || cause instanceof IllegalArgumentException
                || cause instanceof InterruptedException) {
            return true;
        }
        if (cause instanceof SshException sshEx
                && NON_RETRYABLE_DISCONNECT_CODES.contains(sshEx.getDisconnectCode())) {
            return true;
        }
        return hasNonRetryableMessage(cause.getMessage());
    }

    private boolean hasNonRetryableMessage(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String marker : NON_RETRYABLE_MESSAGE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private long computeJitteredBackoff(long baseDelayMs, long maxBackoffMs) {
        if (baseDelayMs <= 0 || maxBackoffMs <= 0) {
            return 0L;
        }
        long capped = Math.min(baseDelayMs, maxBackoffMs);
        double jitterFactor = ThreadLocalRandom.current().nextDouble(0.85, 1.15);
        return Math.clamp(Math.round(capped * jitterFactor), 0L, maxBackoffMs);
    }

    private void sleepUninterruptibly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SftpUploadException("SFTP upload retry interrupted", ex);
        }
    }

    String summarizeException(Throwable ex) {
        Throwable root = ex;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 32) {
            root = root.getCause();
        }
        String rawMessage = root.getMessage();
        String cleaned = rawMessage == null ? "No error message" : rawMessage.replaceAll("[\\p{Cntrl}]+", " ");
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200) + "...";
        }
        return root.getClass().getSimpleName() + ": " + cleaned;
    }

    private record TransferPaths(Path localFile, String fileName, String tempRemotePath, String finalRemotePath) {
    }

    public static class SftpUploadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SftpUploadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
