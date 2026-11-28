package com.enterprise.sfgexporter.sftp;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.model.RetryConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AtomicSftpUploaderIntegrationTest {

    @TempDir
    Path tempDir;

    private SshServer sshServer;
    private Path sftpRootDir;
    private Path knownHostsFile;
    private int serverPort;
    private KeyPair serverHostKeyPair;
    private KeyPair clientAuthKeyPair;
    private Path clientPrivateKeyFile;
    private SftpSessionFactoryRegistry registry;

    @BeforeEach
    void startEmbeddedSftpServer() throws Exception {
        sftpRootDir = Files.createDirectories(tempDir.resolve("sftp-root"));
        Files.createDirectories(sftpRootDir.resolve("inbound/finance"));

        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        serverHostKeyPair = keyGen.generateKeyPair();
        clientAuthKeyPair = keyGen.generateKeyPair();

        // Write client RSA private key in PKCS#8 PEM format with strict 0600 permissions
        clientPrivateKeyFile = tempDir.resolve("client_id_rsa.pem");
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(clientAuthKeyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(clientPrivateKeyFile, pem, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(clientPrivateKeyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        }

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setHost("127.0.0.1");
        sshServer.setPort(0); // ephemeral port
        sshServer.setKeyPairProvider(KeyPairProvider.wrap(serverHostKeyPair));
        sshServer.setPublickeyAuthenticator((username, key, session) ->
                "sfgkeyuser".equals(username) && clientAuthKeyPair.getPublic().equals(key));
        sshServer.setPasswordAuthenticator((username, password, session) ->
                "sfguser".equals(username) && "secret-pass".equals(password));
        sshServer.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshServer.setFileSystemFactory(new VirtualFileSystemFactory(sftpRootDir));
        sshServer.start();
        serverPort = sshServer.getPort();

        // Write pinned OpenSSH known_hosts entry for [127.0.0.1]:<port>
        knownHostsFile = tempDir.resolve("known_hosts");
        StringBuilder entry = new StringBuilder();
        entry.append("[127.0.0.1]:").append(serverPort).append(' ');
        PublicKeyEntry.appendPublicKeyEntry(entry, serverHostKeyPair.getPublic());
        entry.append('\n');
        Files.writeString(knownHostsFile, entry.toString(), StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null) {
            registry.destroy();
        }
        if (sshServer != null && sshServer.isStarted()) {
            sshServer.stop(true);
        }
    }

    /**
     * Verifies end-to-end atomic SFTP upload ({@code .tmp} write followed by atomic rename to {@code .csv})
     * against an embedded Apache MINA SSHD server with password auth, pinned {@code known_hosts}
     * host-key verification, and automatic zeroing of in-memory password credentials after client startup.
     */
    @Test
    void uploadsAtomicallyWithStrictHostKeyVerification() throws Exception {
        Path localCsv = tempDir.resolve("GL_JOURNAL_20261122_060000.csv");
        String payload = "ID,AMOUNT\n1,500.00\n";
        Files.writeString(localCsv, payload, StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString()).build();
        assertThat(target.hasPasswordAuth()).isTrue();
        AppProperties props = AppProperties.builder()
                .sftpTargets(Map.of("sfg-test", target))
                .build();

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        String remotePath = uploader.uploadAtomically(localCsv, "sfg-test", "/finance");

        assertThat(target.hasPasswordAuth()).isFalse();
        assertThat(target.exposePassword()).isNull();
        assertThat(remotePath).isEqualTo("/inbound/finance/GL_JOURNAL_20261122_060000.csv");

        Path expectedRemoteFile = sftpRootDir.resolve("inbound/finance/GL_JOURNAL_20261122_060000.csv");
        Path unexpectedTempFile = sftpRootDir.resolve("inbound/finance/GL_JOURNAL_20261122_060000.csv.tmp");

        assertThat(Files.exists(expectedRemoteFile)).isTrue();
        assertThat(Files.exists(unexpectedTempFile)).isFalse();
        assertThat(Files.readString(expectedRemoteFile, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    /**
     * Verifies end-to-end atomic SFTP upload using PKCS#8 PEM SSH private-key authentication
     * (with {@code 0600} key permissions) and strict {@code known_hosts} verification.
     */
    @Test
    void uploadsAtomicallyUsingSshPrivateKeyAuth() throws Exception {
        Path localCsv = tempDir.resolve("KEY_AUTH_20261122_060000.csv");
        String payload = "ACCOUNT,BALANCE\nACC_99,1000.50\n";
        Files.writeString(localCsv, payload, StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .username("sfgkeyuser")
                .password((String) null)
                .privateKeyPath(clientPrivateKeyFile.toString())
                .build();

        AppProperties props = AppProperties.builder()
                .sftpTargets(Map.of("sfg-key", target))
                .build();

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        String remotePath = uploader.uploadAtomically(localCsv, "sfg-key", "/finance");

        assertThat(remotePath).isEqualTo("/inbound/finance/KEY_AUTH_20261122_060000.csv");
        Path expectedRemoteFile = sftpRootDir.resolve("inbound/finance/KEY_AUTH_20261122_060000.csv");
        assertThat(Files.exists(expectedRemoteFile)).isTrue();
        assertThat(Files.readString(expectedRemoteFile, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    /**
     * Verifies that an SFTP server presenting an unknown or unpinned host key not present in
     * {@code known_hosts} is rejected immediately (MITM protection via {@code RejectAllServerKeyVerifier}).
     */
    @Test
    void rejectsUnknownServerHostKeyStrictly() throws Exception {
        Path emptyKnownHosts = tempDir.resolve("empty_known_hosts");
        Files.writeString(emptyKnownHosts, "", StandardCharsets.UTF_8);

        Path localCsv = tempDir.resolve("REJECT_TEST_20261122_060000.csv");
        Files.writeString(localCsv, "A,B\n1,2\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(emptyKnownHosts.toString()).build();
        AppProperties props = AppProperties.builder()
                .sftpTargets(Map.of("sfg-test", target))
                .build();

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-test", "/finance"))
                .isInstanceOf(AtomicSftpUploader.SftpUploadException.class);
    }

    /**
     * Verifies that transient network/session failures are retried with jittered exponential backoff
     * until a subsequent attempt succeeds within {@code maxAttempts}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void retriesTransientFailuresWithExponentialBackoffAndSucceeds() throws Exception {
        Path localCsv = tempDir.resolve("RETRY_TEST_20261122_060000.csv");
        Files.writeString(localCsv, "COL1\nVAL1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .retry(RetryConfig.builder()
                        .maxAttempts(3)
                        .initialBackoffMs(10L)
                        .multiplier(2.0)
                        .maxBackoffMs(50L)
                        .build())
                .build();

        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        SessionFactory<SftpClient.DirEntry> mockFactory = mock(SessionFactory.class);
        Session<SftpClient.DirEntry> okSession = mock(Session.class);

        AtomicInteger attempts = new AtomicInteger();
        when(mockRegistry.getTargetConfig("sfg-retry")).thenReturn(target);
        when(mockRegistry.getSessionFactory("sfg-retry")).thenReturn(mockFactory);
        when(mockFactory.getSession()).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Transient socket reset", new IOException("Connection reset"));
            }
            return okSession;
        });

        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);
        String remotePath = uploader.uploadAtomically(localCsv, "sfg-retry", "/finance");

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(remotePath).isEqualTo("/inbound/finance/RETRY_TEST_20261122_060000.csv");
    }

    /**
     * Verifies that if streaming to the remote {@code .tmp} file fails mid-transfer while the SFTP
     * session is still open, the partial remote {@code .tmp} file is removed so orphans do not accumulate.
     */
    @Test
    @SuppressWarnings("unchecked")
    void cleansUpPartialRemoteTempFileWhenWriteFailsMidTransfer() throws Exception {
        Path localCsv = tempDir.resolve("PARTIAL_FAIL_20261122_060000.csv");
        Files.writeString(localCsv, "COL1\nVAL1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .retry(RetryConfig.builder().maxAttempts(1).initialBackoffMs(50L).maxBackoffMs(200L).build())
                .build();

        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        SessionFactory<SftpClient.DirEntry> mockFactory = mock(SessionFactory.class);
        Session<SftpClient.DirEntry> failingSession = mock(Session.class);

        String expectedTempPath = "/inbound/finance/PARTIAL_FAIL_20261122_060000.csv.tmp";
        when(mockRegistry.getTargetConfig("sfg-partial")).thenReturn(target);
        when(mockRegistry.getSessionFactory("sfg-partial")).thenReturn(mockFactory);
        when(mockFactory.getSession()).thenReturn(failingSession);
        when(failingSession.isOpen()).thenReturn(true);
        doThrow(new IOException("Mid-stream socket drop"))
                .when(failingSession).write(any(), eq(expectedTempPath));
        when(failingSession.exists(expectedTempPath)).thenReturn(true);

        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);
        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-partial", "/finance"))
                .isInstanceOf(AtomicSftpUploader.SftpUploadException.class);

        verify(failingSession).remove(expectedTempPath);
    }

    /**
     * Verifies that remote {@code .tmp} cleanup is skipped cleanly when the underlying SFTP transport
     * session is already closed, avoiding secondary exceptions masking the original transfer error.
     */
    @Test
    @SuppressWarnings("unchecked")
    void skipsRemoteTempCleanupWhenSessionIsAlreadyClosed() throws Exception {
        Path localCsv = tempDir.resolve("DEAD_SESSION_20261122_060000.csv");
        Files.writeString(localCsv, "COL1\nVAL1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .retry(RetryConfig.builder().maxAttempts(1).initialBackoffMs(50L).maxBackoffMs(200L).build())
                .build();

        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        SessionFactory<SftpClient.DirEntry> mockFactory = mock(SessionFactory.class);
        Session<SftpClient.DirEntry> deadSession = mock(Session.class);

        String expectedTempPath = "/inbound/finance/DEAD_SESSION_20261122_060000.csv.tmp";
        when(mockRegistry.getTargetConfig("sfg-dead")).thenReturn(target);
        when(mockRegistry.getSessionFactory("sfg-dead")).thenReturn(mockFactory);
        when(mockFactory.getSession()).thenReturn(deadSession);
        when(deadSession.isOpen()).thenReturn(false);
        doThrow(new IOException("Transport closed")).when(deadSession).write(any(), eq(expectedTempPath));

        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);
        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-dead", "/finance"))
                .isInstanceOf(AtomicSftpUploader.SftpUploadException.class);

        verify(deadSession, never()).exists(any());
        verify(deadSession, never()).remove(any());
    }

    /**
     * Verifies that {@link SshClientFactory} refuses to load an SSH private key file whose POSIX
     * permissions grant group or world access (e.g., {@code 0644}).
     */
    @Test
    void rejectsOverlyPermissiveSshPrivateKey() throws Exception {
        try {
            Files.setPosixFilePermissions(clientPrivateKeyFile, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ex) {
            return; // Non-POSIX filesystem
        }

        Path localCsv = tempDir.resolve("BAD_KEY_PERMS_20261122_060000.csv");
        Files.writeString(localCsv, "A\n1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .username("sfgkeyuser")
                .password((String) null)
                .privateKeyPath(clientPrivateKeyFile.toString())
                .build();

        AppProperties props = AppProperties.builder()
                .sftpTargets(Map.of("sfg-bad-key", target))
                .build();

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-bad-key", "/finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open");
    }

    /**
     * Verifies that {@link AtomicSftpUploader} refuses to upload symbolic links from the local filesystem
     * and blocks path-traversal segments or slashes inside remote filenames.
     */
    @Test
    void rejectsSymlinkLocalFileAndSlashesInFileName() throws Exception {
        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);

        assertThatThrownBy(() -> uploader.buildRemotePath("/inbound", "/finance", "../evil.csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fileName");

        Path outsideTarget = Files.writeString(tempDir.resolve("secret.csv"), "SECRET\n", StandardCharsets.UTF_8);
        Path symlinkCsv;
        try {
            symlinkCsv = Files.createSymbolicLink(tempDir.resolve("link.csv"), outsideTarget);
        } catch (FileSystemException | UnsupportedOperationException ex) {
            Assumptions.assumeTrue(false,
                    "Skipping symlink assertion (OS privilege unavailable): " + ex.getMessage());
            return;
        }

        assertThatThrownBy(() -> uploader.uploadAtomically(symlinkCsv, "sfg-test", "/finance"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symbolic link");
    }

    /**
     * Verifies bounded cause-chain traversal (preventing infinite loops on cyclic exception causes)
     * and log-injection sanitization of CR/LF/ANSI control characters in error summaries.
     */
    @Test
    void handlesCyclicExceptionCausesAndSanitizesControlChars() {
        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);

        RuntimeException outer = new RuntimeException("line1\r\n\u001Bline2");
        RuntimeException inner = new RuntimeException("line1\r\n\u001Bline2", outer);
        outer.initCause(inner);

        assertThat(uploader.isNonRetryable(outer)).isFalse();
        String summary = uploader.summarizeException(outer);
        assertThat(summary)
                .doesNotContain("\r", "\n", "\u001B")
                .contains("line1 line2");
    }

    /**
     * Verifies defense-in-depth inside {@link SshClientFactory}: weak SSH ciphers (such as {@code 3des-cbc})
     * and world-writable {@code known_hosts} files are rejected even if {@code SecurityValidator} was bypassed.
     */
    @Test
    void sshClientFactoryRejectsWorldWritableKnownHostsAndWeakCipherDirectly() throws Exception {
        SshClientFactory factory = new SshClientFactory();

        SftpTargetConfig weakCipherTarget = createTargetConfigBuilder(knownHostsFile.toString())
                .allowedCiphers(List.of("3des-cbc"))
                .build();
        assertThatThrownBy(() -> factory.createHardenedClient("sfg-weak", weakCipherTarget))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Disallowed or weak");

        try {
            Files.setPosixFilePermissions(knownHostsFile, PosixFilePermissions.fromString("rw-r--rw-"));
        } catch (UnsupportedOperationException ex) {
            return; // Non-POSIX filesystem
        }
        SftpTargetConfig openKnownHostsTarget = createTargetConfigBuilder(knownHostsFile.toString()).build();
        assertThatThrownBy(() -> factory.createHardenedClient("sfg-open-kh", openKnownHostsTarget))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("world-writable");
    }

    /**
     * Verifies that the per-target concurrency {@link java.util.concurrent.Semaphore} bulkhead is
     * released immediately before sleeping for retry backoff so other concurrent jobs sharing the
     * same SFG target are not starved during backoff delays.
     */
    @Test
    @SuppressWarnings("unchecked")
    void releasesBulkheadDuringRetryBackoffSoConcurrentUploadSucceeds() throws Exception {
        Path localCsv1 = Files.writeString(tempDir.resolve("CONCURRENT_1.csv"), "A\n1\n", StandardCharsets.UTF_8);
        Path localCsv2 = Files.writeString(tempDir.resolve("CONCURRENT_2.csv"), "A\n2\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .maxConcurrentUploads(1)
                .connectTimeoutMs(150L)
                .retry(RetryConfig.builder()
                        .maxAttempts(2)
                        .initialBackoffMs(500L)
                        .multiplier(1.0)
                        .maxBackoffMs(500L)
                        .build())
                .build();

        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        SessionFactory<SftpClient.DirEntry> mockFactory = mock(SessionFactory.class);
        Session<SftpClient.DirEntry> okSession = mock(Session.class);

        CountDownLatch firstAttemptEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(mockRegistry.getTargetConfig("sfg-bulkhead")).thenReturn(target);
        when(mockRegistry.getSessionFactory("sfg-bulkhead")).thenReturn(mockFactory);
        when(mockFactory.getSession()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                firstAttemptEntered.countDown();
                throw new IllegalStateException("Transient error", new IOException("Reset"));
            }
            return okSession;
        });

        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> firstUpload = executor.submit(
                    () -> uploader.uploadAtomically(localCsv1, "sfg-bulkhead", "/finance"));

            assertThat(firstAttemptEntered.await(2, TimeUnit.SECONDS)).isTrue();

            String secondResult = uploader.uploadAtomically(localCsv2, "sfg-bulkhead", "/finance");
            assertThat(secondResult).isEqualTo("/inbound/finance/CONCURRENT_2.csv");
            assertThat(firstUpload.get(5, TimeUnit.SECONDS)).isEqualTo("/inbound/finance/CONCURRENT_1.csv");
        }
    }

    /**
     * Verifies fast-fail behavior on non-retryable authentication errors (preventing SFG account lockout)
     * and confirms the thrown exception reports the actual number of attempts executed ({@code 1}), not {@code maxAttempts}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void reportsActualAttemptCountOnEarlyNonRetryableFailure() throws Exception {
        Path localCsv = Files.writeString(tempDir.resolve("AUTH_FAIL.csv"), "A\n1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfigBuilder(knownHostsFile.toString())
                .retry(RetryConfig.builder()
                        .maxAttempts(4)
                        .initialBackoffMs(50L)
                        .maxBackoffMs(100L)
                        .build())
                .build();

        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        SessionFactory<SftpClient.DirEntry> mockFactory = mock(SessionFactory.class);
        AtomicInteger calls = new AtomicInteger();
        when(mockRegistry.getTargetConfig("sfg-auth-fail")).thenReturn(target);
        when(mockRegistry.getSessionFactory("sfg-auth-fail")).thenReturn(mockFactory);
        when(mockFactory.getSession()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw new IllegalStateException("Authentication failed");
        });

        AtomicSftpUploader uploader = new AtomicSftpUploader(mockRegistry);
        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-auth-fail", "/finance"))
                .isInstanceOf(AtomicSftpUploader.SftpUploadException.class)
                .hasMessageContaining("after 1 attempt(s)");
        assertThat(calls.get()).isEqualTo(1);
    }

    /**
     * Verifies concurrency bulkhead timeout protection when an SFTP target's active upload slots remain
     * saturated past {@code connectTimeoutMs}, plus SSH disconnect-code fast-fail classification,
     * unknown target lookup rejection, and remote path slash normalization.
     */
    @Test
    @SuppressWarnings("unchecked")
    void enforcesBulkheadSaturationTimeoutDisconnectCodesUnknownTargetAndRemotePathJoining() throws Exception {
        // 1. Unknown target lookup fails fast
        SftpSessionFactoryRegistry emptyRegistry = new SftpSessionFactoryRegistry(
                AppProperties.builder().sftpTargets(Map.of()).build(), new SshClientFactory());
        assertThatThrownBy(() -> emptyRegistry.getTargetConfig("missing-target"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown SFTP target");

        // 2. SSH disconnect code (SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE = 14) is classified non-retryable
        AtomicSftpUploader uploaderForChecks = new AtomicSftpUploader(emptyRegistry);
        SshException authDisconnect = new SshException(14, "No auth");
        assertThat(uploaderForChecks.isNonRetryable(authDisconnect)).isTrue();

        // 3. Remote path normalization across empty/relative/absolute segments
        assertThat(uploaderForChecks.buildRemotePath("/inbound/", "/finance/", "GL.csv"))
                .isEqualTo("/inbound/finance/GL.csv");
        assertThat(uploaderForChecks.buildRemotePath("", "/finance", "GL.csv"))
                .isEqualTo("/finance/GL.csv");
        assertThat(uploaderForChecks.buildRemotePath("inbound", "", "GL.csv"))
                .isEqualTo("inbound/GL.csv");

        // 4. Bulkhead saturation timeout when active transfer holds the single permit
        Path localCsv1 = Files.writeString(tempDir.resolve("SAT_1.csv"), "A\n1\n", StandardCharsets.UTF_8);
        Path localCsv2 = Files.writeString(tempDir.resolve("SAT_2.csv"), "A\n2\n", StandardCharsets.UTF_8);
        SftpTargetConfig saturatedTarget = createTargetConfigBuilder(knownHostsFile.toString())
                .maxConcurrentUploads(1)
                .connectTimeoutMs(60L)
                .build();

        SftpSessionFactoryRegistry mockRegistry = mock(SftpSessionFactoryRegistry.class);
        SessionFactory<SftpClient.DirEntry> mockFactory = mock(SessionFactory.class);
        Session<SftpClient.DirEntry> blockingSession = mock(Session.class);
        CountDownLatch inTransfer = new CountDownLatch(1);
        CountDownLatch releaseTransfer = new CountDownLatch(1);

        when(mockRegistry.getTargetConfig("sfg-sat")).thenReturn(saturatedTarget);
        when(mockRegistry.getSessionFactory("sfg-sat")).thenReturn(mockFactory);
        when(mockFactory.getSession()).thenAnswer(inv -> {
            inTransfer.countDown();
            if (!releaseTransfer.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Timed out");
            }
            return blockingSession;
        });

        AtomicSftpUploader saturatedUploader = new AtomicSftpUploader(mockRegistry);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> holder = executor.submit(
                    () -> saturatedUploader.uploadAtomically(localCsv1, "sfg-sat", "/finance"));
            assertThat(inTransfer.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> saturatedUploader.uploadAtomically(localCsv2, "sfg-sat", "/finance"))
                    .isInstanceOf(AtomicSftpUploader.SftpUploadException.class)
                    .hasMessageContaining("bulkhead saturated");

            releaseTransfer.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("/inbound/finance/SAT_1.csv");
        }
    }

    private SftpTargetConfig.Builder createTargetConfigBuilder(String knownHostsPath) {
        return SftpTargetConfig.builder()
                .host("127.0.0.1")
                .port(serverPort)
                .username("sfguser")
                .password("secret-pass")
                .knownHostsPath(knownHostsPath)
                .strictHostKeyChecking(true)
                .remoteBaseDir("/inbound")
                .tempSuffix(".tmp")
                .retry(RetryConfig.builder()
                        .maxAttempts(2)
                        .initialBackoffMs(50L)
                        .maxBackoffMs(200L)
                        .build());
    }
}
