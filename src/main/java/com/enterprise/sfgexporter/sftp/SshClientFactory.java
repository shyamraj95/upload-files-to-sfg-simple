package com.enterprise.sfgexporter.sftp;

import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.OptionalFeature;
import org.apache.sshd.common.cipher.BuiltinCiphers;
import org.apache.sshd.common.cipher.Cipher;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.mac.Mac;
import org.apache.sshd.core.CoreModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds hardened Apache MINA {@link SshClient} instances for IBM Sterling File Gateway (SFG) targets.
 *
 * <p>Spring Integration SFTP 6+/7+ requires that when an externally configured {@link SshClient} is
 * passed into {@code DefaultSftpSessionFactory}, host-key verification and authentication credentials
 * must be attached directly to the {@link SshClient} rather than the session factory.</p>
 *
 * <p>Security controls enforced per target:</p>
 * <ul>
 *     <li>Strict {@code known_hosts} pinning backed by {@link RejectAllServerKeyVerifier} (unknown or
 *         rotated host keys are rejected immediately).</li>
 *     <li>Restricted allowlists for modern strong ciphers, key exchanges (KEX), and MACs only.</li>
 *     <li>SSH private-key authentication preferred over password auth; password mode explicitly clears
 *         default {@code ~/.ssh} key discovery via {@link KeyIdentityProvider#EMPTY_KEYS_PROVIDER}.</li>
 *     <li>Explicit connect, authentication, and idle timeouts.</li>
 * </ul>
 */
@Component
public class SshClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SshClientFactory.class);

    public SshClient createHardenedClient(String targetKey, SftpTargetConfig config) {
        SshClient client = SshClient.setUpDefaultClient();
        boolean initialized = false;
        try {
            // 1. Restrict ciphers, KEX, and MACs to configured modern strong algorithms
            client.setCipherFactories(resolveCiphers(targetKey, config.allowedCiphers()));
            client.setKeyExchangeFactories(resolveKeyExchanges(targetKey, config.allowedKeyExchanges()));
            client.setMacFactories(resolveMacs(targetKey, config.allowedMacs()));

            // 2. Enforce strict host key verification via server-mounted known_hosts
            if (config.strictHostKeyChecking()) {
                Path knownHostsPath = Path.of(config.knownHostsPath()).toAbsolutePath().normalize();
                if (Files.isSymbolicLink(knownHostsPath)
                        || !Files.isRegularFile(knownHostsPath, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isReadable(knownHostsPath)) {
                    throw new IllegalStateException(
                            "Cannot initialize SFTP target '" + targetKey
                                    + "': known_hosts file must be a readable non-symlink regular file at " + knownHostsPath);
                }
                rejectWorldWritableFile(knownHostsPath, targetKey);
                // RejectAllServerKeyVerifier delegate guarantees unknown hosts are rejected
                client.setServerKeyVerifier(
                        new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHostsPath));
            } else {
                log.warn("SFTP target '{}' configured with strictHostKeyChecking=false (non-production mode)", targetKey);
                client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            }

            // 3. Configure authentication strictly on the external SshClient (SSH private key preferred)
            configureAuthentication(client, targetKey, config);

            // 4. Configure deterministic network, read, keepalive, and authentication timeouts
            Duration ioTimeout = Duration.ofMillis(Math.max(config.channelOpenTimeoutMs() * 2L, 30_000L));
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, Duration.ofMillis(config.connectTimeoutMs()));
            CoreModuleProperties.AUTH_TIMEOUT.set(client, Duration.ofMillis(config.authTimeoutMs()));
            CoreModuleProperties.IDLE_TIMEOUT.set(client, ioTimeout);
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(client, ioTimeout);
            CoreModuleProperties.SOCKET_KEEPALIVE.set(client, true);

            client.start();
            // Release plaintext credential references from the config object once absorbed by SshClient
            config.clearSecrets();
            log.info("Started hardened SshClient for target '{}' (host={}:{}, strictHostKeyChecking={})",
                    targetKey, config.host(), config.port(), config.strictHostKeyChecking());
            initialized = true;
            return client;
        } finally {
            if (!initialized) {
                closeClientQuietly(client);
            }
        }
    }

    private void closeClientQuietly(SshClient client) {
        try {
            client.close(true);
        } catch (RuntimeException cleanupEx) {
            log.warn("Error closing SshClient after initialization failure: {}", cleanupEx.getMessage());
        }
    }

    private void configureAuthentication(SshClient client, String targetKey, SftpTargetConfig config) {
        if (config.hasPrivateKeyAuth()) {
            Path keyPath = Path.of(config.privateKeyPath()).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(keyPath)
                    || !Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isReadable(keyPath)) {
                throw new IllegalStateException(
                        "SSH private key for target '" + targetKey
                                + "' does not exist, is a symlink, or is not readable: " + keyPath);
            }
            enforcePrivateKeyPermissions(keyPath, targetKey);
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyPath);
            String passphrase = config.exposePrivateKeyPassphrase();
            if (passphrase != null && !passphrase.isEmpty()) {
                keyPairProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
            }
            client.setKeyIdentityProvider(keyPairProvider);
            log.info("Configured SSH private-key authentication for SFTP target '{}'", targetKey);
        } else if (config.hasPasswordAuth()) {
            // Prevent implicit fallback to ~/.ssh keys when password auth is explicitly selected
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.addPasswordIdentity(config.exposePassword());
            log.info("Configured password authentication for SFTP target '{}'", targetKey);
        } else {
            throw new IllegalStateException("No authentication method configured for SFTP target '" + targetKey + "'");
        }
    }

    private void rejectWorldWritableFile(Path filePath, String targetKey) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(filePath, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalStateException(
                        "known_hosts file permissions for target '" + targetKey
                                + "' are too open (world-writable): " + filePath);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to inspect known_hosts permissions for target '" + targetKey + "'", ex);
        }
    }

    private void enforcePrivateKeyPermissions(Path keyPath, String targetKey) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(keyPath, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.GROUP_READ)
                    || perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.GROUP_EXECUTE)
                    || perms.contains(PosixFilePermission.OTHERS_READ)
                    || perms.contains(PosixFilePermission.OTHERS_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new IllegalStateException(
                        "SSH private key permissions for target '" + targetKey
                                + "' are too open (require 0600): " + keyPath);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to inspect SSH private key permissions for target '" + targetKey + "'", ex);
        }
    }

    private List<NamedFactory<Cipher>> resolveCiphers(String targetKey, List<String> names) {
        return new ArrayList<>(resolveAlgorithms(
                targetKey, "SSH cipher", names, SftpTargetConfig.APPROVED_STRONG_CIPHERS, BuiltinCiphers::fromFactoryName));
    }

    private List<KeyExchangeFactory> resolveKeyExchanges(String targetKey, List<String> names) {
        List<BuiltinDHFactories> selected = resolveAlgorithms(
                targetKey, "SSH key exchange", names, SftpTargetConfig.APPROVED_STRONG_KEX, BuiltinDHFactories::fromFactoryName);
        return NamedFactory.setUpTransformedFactories(false, selected, ClientBuilder.DH2KEX);
    }

    private List<NamedFactory<Mac>> resolveMacs(String targetKey, List<String> names) {
        return new ArrayList<>(resolveAlgorithms(
                targetKey, "SSH MAC", names, SftpTargetConfig.APPROVED_STRONG_MACS, BuiltinMacs::fromFactoryName));
    }

    private <T extends OptionalFeature> List<T> resolveAlgorithms(
            String targetKey,
            String algorithmKind,
            List<String> names,
            Set<String> approvedAllowlist,
            Function<String, T> lookup
    ) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException(
                    "No " + algorithmKind + " algorithms configured for target '" + targetKey + "'");
        }
        List<T> selected = new ArrayList<>(names.size());
        for (String name : names) {
            if (!approvedAllowlist.contains(name)) {
                throw new IllegalArgumentException(
                        "Disallowed or weak " + algorithmKind + " '" + name + "' configured for target '" + targetKey + "'");
            }
            T algorithm = lookup.apply(name);
            if (algorithm == null) {
                throw new IllegalArgumentException(
                        "Unknown " + algorithmKind + " '" + name + "' configured for target '" + targetKey + "'");
            }
            if (algorithm.isSupported()) {
                selected.add(algorithm);
            } else {
                log.debug("Skipping unsupported {} '{}' on current JVM for target '{}'",
                        algorithmKind, name, targetKey);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException(
                    "No supported " + algorithmKind + " algorithms remain for target '" + targetKey + "'");
        }
        return selected;
    }
}
