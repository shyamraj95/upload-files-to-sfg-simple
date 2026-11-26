package com.enterprise.sfgexporter.model;

import java.util.Arrays;

/**
 * Mutable, zeroable character-array wrapper for sensitive configuration secrets
 * (such as SFTP passwords and SSH key passphrases) stored inside immutable configuration records.
 *
 * <p>Provides a single-argument {@link #SecretValue(String)} constructor so Spring Boot's
 * constructor binder can convert YAML/environment strings directly without comma splitting,
 * and {@link #toString()} redacts the secret value.</p>
 */
public final class SecretValue {

    private char[] value;

    public SecretValue(String raw) {
        this.value = raw != null ? raw.toCharArray() : null;
    }

    public static SecretValue of(String raw) {
        return raw == null ? null : new SecretValue(raw);
    }

    public synchronized boolean isPresent() {
        if (value == null || value.length == 0) {
            return false;
        }
        for (char c : value) {
            if (!Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    public synchronized String expose() {
        return value != null ? new String(value) : null;
    }

    public synchronized void clear() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }

    @Override
    public String toString() {
        return "***";
    }
}
