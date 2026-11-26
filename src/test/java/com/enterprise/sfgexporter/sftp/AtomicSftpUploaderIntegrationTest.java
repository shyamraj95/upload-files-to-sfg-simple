package com.enterprise.sfgexporter.sftp;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.model.RetryConfig;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

    @Test
    void uploadsAtomicallyWithStrictHostKeyVerification() throws Exception {
        Path localCsv = tempDir.resolve("GL_JOURNAL_20261122_060000.csv");
        String payload = "ID,AMOUNT\n1,500.00\n";
        Files.writeString(localCsv, payload, StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(knownHostsFile.toString());
        AppProperties props = new AppProperties();
        props.setSftpTargets(Map.of("sfg-test", target));

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        String remotePath = uploader.uploadAtomically(localCsv, "sfg-test", "/finance");

        assertThat(remotePath).isEqualTo("/inbound/finance/GL_JOURNAL_20261122_060000.csv");

        Path expectedRemoteFile = sftpRootDir.resolve("inbound/finance/GL_JOURNAL_20261122_060000.csv");
        Path unexpectedTempFile = sftpRootDir.resolve("inbound/finance/GL_JOURNAL_20261122_060000.csv.tmp");

        assertThat(Files.exists(expectedRemoteFile)).isTrue();
        assertThat(Files.exists(unexpectedTempFile)).isFalse();
        assertThat(Files.readString(expectedRemoteFile, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void uploadsAtomicallyUsingSshPrivateKeyAuth() throws Exception {
        Path localCsv = tempDir.resolve("KEY_AUTH_20261122_060000.csv");
        String payload = "ACCOUNT,BALANCE\nACC_99,1000.50\n";
        Files.writeString(localCsv, payload, StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(knownHostsFile.toString());
        target.setUsername("sfgkeyuser");
        target.setPassword(null);
        target.setPrivateKeyPath(clientPrivateKeyFile.toString());

        AppProperties props = new AppProperties();
        props.setSftpTargets(Map.of("sfg-key", target));

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        String remotePath = uploader.uploadAtomically(localCsv, "sfg-key", "/finance");

        assertThat(remotePath).isEqualTo("/inbound/finance/KEY_AUTH_20261122_060000.csv");
        Path expectedRemoteFile = sftpRootDir.resolve("inbound/finance/KEY_AUTH_20261122_060000.csv");
        assertThat(Files.exists(expectedRemoteFile)).isTrue();
        assertThat(Files.readString(expectedRemoteFile, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void rejectsUnknownServerHostKeyStrictly() throws Exception {
        Path emptyKnownHosts = tempDir.resolve("empty_known_hosts");
        Files.writeString(emptyKnownHosts, "", StandardCharsets.UTF_8);

        Path localCsv = tempDir.resolve("REJECT_TEST_20261122_060000.csv");
        Files.writeString(localCsv, "A,B\n1,2\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(emptyKnownHosts.toString());
        AppProperties props = new AppProperties();
        props.setSftpTargets(Map.of("sfg-test", target));

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-test", "/finance"))
                .isInstanceOf(AtomicSftpUploader.SftpUploadException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesTransientFailuresWithExponentialBackoffAndSucceeds() throws Exception {
        Path localCsv = tempDir.resolve("RETRY_TEST_20261122_060000.csv");
        Files.writeString(localCsv, "COL1\nVAL1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(knownHostsFile.toString());
        RetryConfig retry = new RetryConfig();
        retry.setMaxAttempts(3);
        retry.setInitialBackoffMs(10L);
        retry.setMultiplier(2.0);
        retry.setMaxBackoffMs(50L);
        target.setRetry(retry);

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

    @Test
    @SuppressWarnings("unchecked")
    void cleansUpPartialRemoteTempFileWhenWriteFailsMidTransfer() throws Exception {
        Path localCsv = tempDir.resolve("PARTIAL_FAIL_20261122_060000.csv");
        Files.writeString(localCsv, "COL1\nVAL1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(knownHostsFile.toString());
        target.getRetry().setMaxAttempts(1);

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

    @Test
    @SuppressWarnings("unchecked")
    void skipsRemoteTempCleanupWhenSessionIsAlreadyClosed() throws Exception {
        Path localCsv = tempDir.resolve("DEAD_SESSION_20261122_060000.csv");
        Files.writeString(localCsv, "COL1\nVAL1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(knownHostsFile.toString());
        target.getRetry().setMaxAttempts(1);

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

    @Test
    void rejectsOverlyPermissiveSshPrivateKey() throws Exception {
        try {
            Files.setPosixFilePermissions(clientPrivateKeyFile, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ex) {
            return; // Non-POSIX filesystem
        }

        Path localCsv = tempDir.resolve("BAD_KEY_PERMS_20261122_060000.csv");
        Files.writeString(localCsv, "A\n1\n", StandardCharsets.UTF_8);

        SftpTargetConfig target = createTargetConfig(knownHostsFile.toString());
        target.setUsername("sfgkeyuser");
        target.setPassword(null);
        target.setPrivateKeyPath(clientPrivateKeyFile.toString());

        AppProperties props = new AppProperties();
        props.setSftpTargets(Map.of("sfg-bad-key", target));

        registry = new SftpSessionFactoryRegistry(props, new SshClientFactory());
        AtomicSftpUploader uploader = new AtomicSftpUploader(registry);

        assertThatThrownBy(() -> uploader.uploadAtomically(localCsv, "sfg-bad-key", "/finance"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too open");
    }

    private SftpTargetConfig createTargetConfig(String knownHostsPath) {
        SftpTargetConfig target = new SftpTargetConfig();
        target.setHost("127.0.0.1");
        target.setPort(serverPort);
        target.setUsername("sfguser");
        target.setPassword("secret-pass");
        target.setKnownHostsPath(knownHostsPath);
        target.setStrictHostKeyChecking(true);
        target.setRemoteBaseDir("/inbound");
        target.setTempSuffix(".tmp");
        RetryConfig retry = new RetryConfig();
        retry.setMaxAttempts(2);
        retry.setInitialBackoffMs(50L);
        retry.setMaxBackoffMs(200L);
        target.setRetry(retry);
        return target;
    }
}
