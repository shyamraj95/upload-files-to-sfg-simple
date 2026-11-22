package com.enterprise.sfgexporter.sftp;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.model.SftpTargetConfig;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of Spring Integration {@link DefaultSftpSessionFactory} instances,
 * lazily instantiated once per configured IBM SFG target under {@code app.sftp-targets}.
 *
 * <p>Tracks each target's underlying Apache MINA {@link SshClient} alongside its session factory
 * in a single {@link ConcurrentHashMap} and stops all active clients cleanly during Spring shutdown
 * via {@link DisposableBean#destroy()}.</p>
 */
@Component
public class SftpSessionFactoryRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SftpSessionFactoryRegistry.class);

    private final AppProperties properties;
    private final SshClientFactory sshClientFactory;
    private final Map<String, ManagedTarget> targets = new ConcurrentHashMap<>();

    public SftpSessionFactoryRegistry(AppProperties properties, SshClientFactory sshClientFactory) {
        this.properties = properties;
        this.sshClientFactory = sshClientFactory;
    }

    public SftpTargetConfig getTargetConfig(String targetKey) {
        SftpTargetConfig config = properties.getSftpTargets().get(targetKey);
        if (config == null) {
            throw new IllegalArgumentException("Unknown SFTP target: " + targetKey);
        }
        return config;
    }

    public SessionFactory<SftpClient.DirEntry> getSessionFactory(String targetKey) {
        return targets.computeIfAbsent(targetKey, key -> createManagedTarget(key, getTargetConfig(key))).factory();
    }

    private ManagedTarget createManagedTarget(String targetKey, SftpTargetConfig config) {
        SshClient sshClient = sshClientFactory.createHardenedClient(targetKey, config);
        try {
            // Pass the externally hardened SshClient directly; auth & strict host-key verification
            // are enforced directly on sshClient as required by Spring Integration SFTP 6+/7+.
            DefaultSftpSessionFactory factory = new DefaultSftpSessionFactory(sshClient, false);
            factory.setHost(config.getHost());
            factory.setPort(config.getPort());
            factory.setUser(config.getUsername());
            factory.setTimeout((int) Math.min(config.getChannelOpenTimeoutMs(), Integer.MAX_VALUE));
            return new ManagedTarget(sshClient, factory);
        } catch (RuntimeException ex) {
            stopClientQuietly(sshClient);
            throw ex;
        }
    }

    @Override
    public void destroy() {
        for (ManagedTarget target : targets.values()) {
            stopClientQuietly(target.client());
        }
        targets.clear();
        log.info("Stopped all managed SFTP SshClient instances");
    }

    private void stopClientQuietly(SshClient client) {
        try {
            if (client.isStarted()) {
                client.stop();
            }
        } catch (RuntimeException ex) {
            log.warn("Error stopping SshClient cleanly: {}", ex.getMessage());
        }
    }

    private record ManagedTarget(SshClient client, SessionFactory<SftpClient.DirEntry> factory) {
    }
}
