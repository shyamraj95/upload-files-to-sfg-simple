package com.enterprise.sfgexporter.model;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Capped exponential backoff retry configuration applied to transient SFTP upload errors.
 *
 * @param maxAttempts      Total number of upload attempts including the initial try (must be &gt;= 1).
 * @param initialBackoffMs Initial delay in milliseconds before the first retry (jittered by +/-15%).
 * @param multiplier       Exponential backoff multiplier applied after each failed attempt (must be &gt;= 1.0).
 * @param maxBackoffMs     Upper bound in milliseconds for any single retry sleep interval.
 */
public record RetryConfig(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("2000") long initialBackoffMs,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("30000") long maxBackoffMs
) {

    public static RetryConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .maxAttempts(maxAttempts)
                .initialBackoffMs(initialBackoffMs)
                .multiplier(multiplier)
                .maxBackoffMs(maxBackoffMs);
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private long initialBackoffMs = 2_000L;
        private double multiplier = 2.0;
        private long maxBackoffMs = 30_000L;

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
            return this;
        }

        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        public Builder maxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
            return this;
        }

        public RetryConfig build() {
            return new RetryConfig(maxAttempts, initialBackoffMs, multiplier, maxBackoffMs);
        }
    }
}
