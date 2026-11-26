package com.enterprise.sfgexporter.model;

import java.util.List;
import java.util.Set;

/**
 * Configuration for a named IBM Sterling File Gateway (SFG) SFTP target server.
 *
 * <p>All credentials, private key paths, passphrases, and {@code known_hosts} paths must be
 * supplied strictly via server environment variables. {@link #toString()} intentionally redacts
 * secret values so they can never leak into application logs.</p>
 */
public class SftpTargetConfig {

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

    /** Remote IBM SFG SFTP hostname or IP address. */
    private String host;

    /** Remote SFTP TCP port (default: 22). */
    private int port = 22;

    /** SFTP login username. */
    private String username;

    /** Absolute path to a server-mounted SSH private key file (preferred over password auth). */
    private String privateKeyPath;

    /** Optional passphrase for an encrypted SSH private key. */
    private String privateKeyPassphrase;

    /** Password authentication fallback when SSH key authentication is not configured. */
    private String password;

    /** Absolute path to a server-mounted OpenSSH {@code known_hosts} file for server identity pinning. */
    private String knownHostsPath;

    /** Enforces strict host key verification against {@code knownHostsPath} (default: {@code true}). */
    private boolean strictHostKeyChecking = true;

    /** TCP connection timeout in milliseconds. */
    private long connectTimeoutMs = 15_000L;

    /** SSH authentication handshake timeout in milliseconds. */
    private long authTimeoutMs = 15_000L;

    /** SFTP subsystem channel open timeout in milliseconds. */
    private long channelOpenTimeoutMs = 15_000L;

    /** Temporary suffix appended during upload before atomic rename to {@code .csv} (default: {@code .tmp}). */
    private String tempSuffix = ".tmp";

    /** Optional remote base mailbox directory prepended to each job's {@code remote-dir}. */
    private String remoteBaseDir = "";

    /** Ordered allowlist of permitted SSH encryption ciphers. */
    private List<String> allowedCiphers = DEFAULT_STRONG_CIPHERS;

    /** Ordered allowlist of permitted SSH key-exchange algorithms. */
    private List<String> allowedKeyExchanges = DEFAULT_STRONG_KEX;

    /** Ordered allowlist of permitted SSH MAC algorithms. */
    private List<String> allowedMacs = DEFAULT_STRONG_MACS;

    /** Exponential backoff retry settings for transient network/I/O SFTP failures. */
    private RetryConfig retry = new RetryConfig();

    /** Maximum concurrent uploads allowed against this SFG target to prevent scheduler starvation. */
    private int maxConcurrentUploads = 2;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPrivateKeyPassphrase() {
        return privateKeyPassphrase;
    }

    public void setPrivateKeyPassphrase(String privateKeyPassphrase) {
        this.privateKeyPassphrase = privateKeyPassphrase;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getKnownHostsPath() {
        return knownHostsPath;
    }

    public void setKnownHostsPath(String knownHostsPath) {
        this.knownHostsPath = knownHostsPath;
    }

    public boolean isStrictHostKeyChecking() {
        return strictHostKeyChecking;
    }

    public void setStrictHostKeyChecking(boolean strictHostKeyChecking) {
        this.strictHostKeyChecking = strictHostKeyChecking;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getAuthTimeoutMs() {
        return authTimeoutMs;
    }

    public void setAuthTimeoutMs(long authTimeoutMs) {
        this.authTimeoutMs = authTimeoutMs;
    }

    public long getChannelOpenTimeoutMs() {
        return channelOpenTimeoutMs;
    }

    public void setChannelOpenTimeoutMs(long channelOpenTimeoutMs) {
        this.channelOpenTimeoutMs = channelOpenTimeoutMs;
    }

    public String getTempSuffix() {
        return tempSuffix;
    }

    public void setTempSuffix(String tempSuffix) {
        this.tempSuffix = tempSuffix;
    }

    public String getRemoteBaseDir() {
        return remoteBaseDir;
    }

    public void setRemoteBaseDir(String remoteBaseDir) {
        this.remoteBaseDir = remoteBaseDir;
    }

    public List<String> getAllowedCiphers() {
        return allowedCiphers;
    }

    public void setAllowedCiphers(List<String> allowedCiphers) {
        this.allowedCiphers = allowedCiphers;
    }

    public List<String> getAllowedKeyExchanges() {
        return allowedKeyExchanges;
    }

    public void setAllowedKeyExchanges(List<String> allowedKeyExchanges) {
        this.allowedKeyExchanges = allowedKeyExchanges;
    }

    public List<String> getAllowedMacs() {
        return allowedMacs;
    }

    public void setAllowedMacs(List<String> allowedMacs) {
        this.allowedMacs = allowedMacs;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    public int getMaxConcurrentUploads() {
        return maxConcurrentUploads;
    }

    public void setMaxConcurrentUploads(int maxConcurrentUploads) {
        this.maxConcurrentUploads = maxConcurrentUploads;
    }

    public boolean hasPrivateKeyAuth() {
        return privateKeyPath != null && !privateKeyPath.isBlank();
    }

    public boolean hasPasswordAuth() {
        return password != null && !password.isBlank();
    }

    /**
     * Releases plaintext secret references once the underlying {@code SshClient} has absorbed them.
     */
    public void clearSecrets() {
        this.password = null;
        this.privateKeyPassphrase = null;
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
}
