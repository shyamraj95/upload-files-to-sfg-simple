package com.enterprise.sfgexporter.model;

import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable configuration record for a named IBM Sterling File Gateway (SFG) SFTP target server.
 *
 * <p>All credentials, private key paths, passphrases, and {@code known_hosts} paths must be
 * supplied strictly via server environment variables. {@link #toString()} intentionally redacts
 * secret values so they can never leak into application logs, and {@link #clearSecrets()} wipes
 * underlying character buffers once absorbed by {@code SshClient}.</p>
 */
public record SftpTargetConfig(
        String host,
        @DefaultValue("22") int port,
        String username,
        String privateKeyPath,
        SecretValue privateKeyPassphrase,
        SecretValue password,
        String knownHostsPath,
        @DefaultValue("true") boolean strictHostKeyChecking,
        @DefaultValue("15000") long connectTimeoutMs,
        @DefaultValue("15000") long authTimeoutMs,
        @DefaultValue("15000") long channelOpenTimeoutMs,
        @DefaultValue(".tmp") String tempSuffix,
        @DefaultValue("") String remoteBaseDir,
        List<String> allowedCiphers,
        List<String> allowedKeyExchanges,
        List<String> allowedMacs,
        @DefaultValue RetryConfig retry,
        @DefaultValue("2") int maxConcurrentUploads
) {

    /** Modern AEAD and CTR symmetric ciphers (weak CBC, 3DES, RC4, Blowfish, and {@code none} excluded). */
    public static final List<String> DEFAULT_STRONG_CIPHERS = List.of(
            "aes256-gcm@openssh.com",
            "aes128-gcm@openssh.com",
            "chacha20-poly1305@openssh.com",
            "aes256-ctr",
            "aes192-ctr",
            "aes128-ctr"
    );

    /** Modern elliptic-curve and SHA-256/512 Diffie-Hellman key exchange algorithms (SHA-1/group1 excluded). */
    public static final List<String> DEFAULT_STRONG_KEX = List.of(
            "curve25519-sha256",
            "ecdh-sha2-nistp521",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp256",
            "diffie-hellman-group18-sha512",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group14-sha256",
            "diffie-hellman-group-exchange-sha256"
    );

    /** Modern SHA-2 MACs preferring Encrypt-then-MAC (MD5 and SHA-1 excluded). */
    public static final List<String> DEFAULT_STRONG_MACS = List.of(
            "hmac-sha2-512-etm@openssh.com",
            "hmac-sha2-256-etm@openssh.com",
            "hmac-sha2-512",
            "hmac-sha2-256"
    );

    public static final Set<String> APPROVED_STRONG_CIPHERS = Set.copyOf(DEFAULT_STRONG_CIPHERS);
    public static final Set<String> APPROVED_STRONG_KEX = Set.copyOf(DEFAULT_STRONG_KEX);
    public static final Set<String> APPROVED_STRONG_MACS = Set.copyOf(DEFAULT_STRONG_MACS);

    public SftpTargetConfig {
        remoteBaseDir = remoteBaseDir != null ? remoteBaseDir : "";
        allowedCiphers = allowedCiphers == null
                ? DEFAULT_STRONG_CIPHERS
                : Collections.unmodifiableList(new ArrayList<>(allowedCiphers));
        allowedKeyExchanges = allowedKeyExchanges == null
                ? DEFAULT_STRONG_KEX
                : Collections.unmodifiableList(new ArrayList<>(allowedKeyExchanges));
        allowedMacs = allowedMacs == null
                ? DEFAULT_STRONG_MACS
                : Collections.unmodifiableList(new ArrayList<>(allowedMacs));
        retry = retry != null ? retry : RetryConfig.defaults();
    }

    public boolean hasPrivateKeyAuth() {
        return privateKeyPath != null && !privateKeyPath.isBlank();
    }

    public boolean hasPasswordAuth() {
        return password != null && password.isPresent();
    }

    public String exposePassword() {
        return password != null ? password.expose() : null;
    }

    public String exposePrivateKeyPassphrase() {
        return privateKeyPassphrase != null ? privateKeyPassphrase.expose() : null;
    }

    /**
     * Wipes plaintext secret references once the underlying {@code SshClient} has absorbed them.
     */
    public void clearSecrets() {
        if (password != null) {
            password.clear();
        }
        if (privateKeyPassphrase != null) {
            privateKeyPassphrase.clear();
        }
    }

    /**
     * Redacts secrets so passwords and passphrases never appear in logs.
     */
    @Override
    public String toString() {
        return "SftpTargetConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                ", authMode=" + (hasPrivateKeyAuth() ? "SSH_KEY" : "PASSWORD") +
                ", strictHostKeyChecking=" + strictHostKeyChecking +
                ", tempSuffix='" + tempSuffix + '\'' +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .host(host)
                .port(port)
                .username(username)
                .privateKeyPath(privateKeyPath)
                .privateKeyPassphrase(privateKeyPassphrase)
                .password(password)
                .knownHostsPath(knownHostsPath)
                .strictHostKeyChecking(strictHostKeyChecking)
                .connectTimeoutMs(connectTimeoutMs)
                .authTimeoutMs(authTimeoutMs)
                .channelOpenTimeoutMs(channelOpenTimeoutMs)
                .tempSuffix(tempSuffix)
                .remoteBaseDir(remoteBaseDir)
                .allowedCiphers(allowedCiphers)
                .allowedKeyExchanges(allowedKeyExchanges)
                .allowedMacs(allowedMacs)
                .retry(retry)
                .maxConcurrentUploads(maxConcurrentUploads);
    }

    public static final class Builder {
        private String host;
        private int port = 22;
        private String username;
        private String privateKeyPath;
        private SecretValue privateKeyPassphrase;
        private SecretValue password;
        private String knownHostsPath;
        private boolean strictHostKeyChecking = true;
        private long connectTimeoutMs = 15_000L;
        private long authTimeoutMs = 15_000L;
        private long channelOpenTimeoutMs = 15_000L;
        private String tempSuffix = ".tmp";
        private String remoteBaseDir = "";
        private List<String> allowedCiphers = DEFAULT_STRONG_CIPHERS;
        private List<String> allowedKeyExchanges = DEFAULT_STRONG_KEX;
        private List<String> allowedMacs = DEFAULT_STRONG_MACS;
        private RetryConfig retry = RetryConfig.defaults();
        private int maxConcurrentUploads = 2;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder privateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
            return this;
        }

        public Builder privateKeyPassphrase(SecretValue privateKeyPassphrase) {
            this.privateKeyPassphrase = privateKeyPassphrase;
            return this;
        }

        public Builder privateKeyPassphrase(String privateKeyPassphrase) {
            this.privateKeyPassphrase = SecretValue.of(privateKeyPassphrase);
            return this;
        }

        public Builder password(SecretValue password) {
            this.password = password;
            return this;
        }

        public Builder password(String password) {
            this.password = SecretValue.of(password);
            return this;
        }

        public Builder knownHostsPath(String knownHostsPath) {
            this.knownHostsPath = knownHostsPath;
            return this;
        }

        public Builder strictHostKeyChecking(boolean strictHostKeyChecking) {
            this.strictHostKeyChecking = strictHostKeyChecking;
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder authTimeoutMs(long authTimeoutMs) {
            this.authTimeoutMs = authTimeoutMs;
            return this;
        }

        public Builder channelOpenTimeoutMs(long channelOpenTimeoutMs) {
            this.channelOpenTimeoutMs = channelOpenTimeoutMs;
            return this;
        }

        public Builder tempSuffix(String tempSuffix) {
            this.tempSuffix = tempSuffix;
            return this;
        }

        public Builder remoteBaseDir(String remoteBaseDir) {
            this.remoteBaseDir = remoteBaseDir;
            return this;
        }

        public Builder allowedCiphers(List<String> allowedCiphers) {
            this.allowedCiphers = allowedCiphers;
            return this;
        }

        public Builder allowedKeyExchanges(List<String> allowedKeyExchanges) {
            this.allowedKeyExchanges = allowedKeyExchanges;
            return this;
        }

        public Builder allowedMacs(List<String> allowedMacs) {
            this.allowedMacs = allowedMacs;
            return this;
        }

        public Builder retry(RetryConfig retry) {
            this.retry = retry;
            return this;
        }

        public Builder maxConcurrentUploads(int maxConcurrentUploads) {
            this.maxConcurrentUploads = maxConcurrentUploads;
            return this;
        }

        public SftpTargetConfig build() {
            return new SftpTargetConfig(
                    host,
                    port,
                    username,
                    privateKeyPath,
                    privateKeyPassphrase,
                    password,
                    knownHostsPath,
                    strictHostKeyChecking,
                    connectTimeoutMs,
                    authTimeoutMs,
                    channelOpenTimeoutMs,
                    tempSuffix,
                    remoteBaseDir,
                    allowedCiphers,
                    allowedKeyExchanges,
                    allowedMacs,
                    retry,
                    maxConcurrentUploads
            );
        }
    }
}
