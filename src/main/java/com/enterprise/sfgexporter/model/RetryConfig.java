package com.enterprise.sfgexporter.model;

/**
 * Capped exponential backoff retry configuration applied to transient SFTP upload errors.
 */
public class RetryConfig {

    /** Total number of upload attempts including the initial try (must be &gt;= 1). */
    private int maxAttempts = 3;

    /** Initial delay in milliseconds before the first retry (jittered by +/-15%). */
    private long initialBackoffMs = 2_000L;

    /** Exponential backoff multiplier applied after each failed attempt (must be &gt;= 1.0). */
    private double multiplier = 2.0;

    /** Upper bound in milliseconds for any single retry sleep interval. */
    private long maxBackoffMs = 30_000L;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }
}
