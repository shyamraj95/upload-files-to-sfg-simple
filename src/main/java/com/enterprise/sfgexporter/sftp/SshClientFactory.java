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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

        // 1. Restrict ciphers, KEX, and MACs to configured modern strong algorithms
        client.setCipherFactories(resolveCiphers(targetKey, config.getAllowedCiphers()));
        client.setKeyExchangeFactories(resolveKeyExchanges(targetKey, config.getAllowedKeyExchanges()));
        client.setMacFactories(resolveMacs(targetKey, config.getAllowedMacs()));

        // 2. Enforce strict host key verification via server-mounted known_hosts
        if (config.isStrictHostKeyChecking()) {
            Path knownHostsPath = Path.of(config.getKnownHostsPath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(knownHostsPath) || !Files.isReadable(knownHostsPath)) {
                throw new IllegalStateException(
                        "Cannot initialize SFTP target '" + targetKey
                                + "': known_hosts file does not exist or is not readable at " + knownHostsPath);
            }
            // RejectAllServerKeyVerifier delegate guarantees unknown hosts are rejected
            client.setServerKeyVerifier(
                    new KnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, knownHostsPath));
        } else {
            log.warn("SFTP target '{}' configured with strictHostKeyChecking=false (non-production mode)", targetKey);
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        }

        // 3. Configure authentication strictly on the external SshClient (SSH private key preferred)
        if (config.hasPrivateKeyAuth()) {
            Path keyPath = Path.of(config.getPrivateKeyPath()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(keyPath) || !Files.isReadable(keyPath)) {
                throw new IllegalStateException(
                        "SSH private key for target '" + targetKey + "' does not exist or is not readable: " + keyPath);
            }
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyPath);
            if (config.getPrivateKeyPassphrase() != null && !config.getPrivateKeyPassphrase().isEmpty()) {
                keyPairProvider.setPasswordFinder(FilePasswordProvider.of(config.getPrivateKeyPassphrase()));
            }
            client.setKeyIdentityProvider(keyPairProvider);
            log.info("Configured SSH private-key authentication for SFTP target '{}'", targetKey);
        } else if (config.hasPasswordAuth()) {
            // Prevent implicit fallback to ~/.ssh keys when password auth is explicitly selected
            client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            client.addPasswordIdentity(config.getPassword());
            log.info("Configured password authentication for SFTP target '{}'", targetKey);
        } else {
            throw new IllegalStateException("No authentication method configured for SFTP target '" + targetKey + "'");
        }

        // 4. Configure deterministic network and authentication timeouts
        CoreModuleProperties.IO_CONNECT_TIMEOUT.set(client, Duration.ofMillis(config.getConnectTimeoutMs()));
        CoreModuleProperties.AUTH_TIMEOUT.set(client, Duration.ofMillis(config.getAuthTimeoutMs()));
        CoreModuleProperties.IDLE_TIMEOUT.set(
                client, Duration.ofMillis(Math.max(config.getChannelOpenTimeoutMs() * 2L, 30_000L)));

        client.start();
        log.info("Started hardened SshClient for target '{}' (host={}:{}, strictHostKeyChecking={})",
                targetKey, config.getHost(), config.getPort(), config.isStrictHostKeyChecking());
        return client;
    }

    private List<NamedFactory<Cipher>> resolveCiphers(String targetKey, List<String> names) {
        return new ArrayList<>(resolveAlgorithms(
                targetKey, "SSH cipher", names, BuiltinCiphers::fromFactoryName));
    }

    private List<KeyExchangeFactory> resolveKeyExchanges(String targetKey, List<String> names) {
        List<BuiltinDHFactories> selected = resolveAlgorithms(
                targetKey, "SSH key exchange", names, BuiltinDHFactories::fromFactoryName);
        return NamedFactory.setUpTransformedFactories(false, selected, ClientBuilder.DH2KEX);
    }

    private List<NamedFactory<Mac>> resolveMacs(String targetKey, List<String> names) {
        return new ArrayList<>(resolveAlgorithms(
                targetKey, "SSH MAC", names, BuiltinMacs::fromFactoryName));
    }

    private <T extends OptionalFeature> List<T> resolveAlgorithms(
            String targetKey,
            String algorithmKind,
            List<String> names,
            Function<String, T> lookup
    ) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException(
                    "No " + algorithmKind + " algorithms configured for target '" + targetKey + "'");
        }
        List<T> selected = new ArrayList<>(names.size());
        for (String name : names) {
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
