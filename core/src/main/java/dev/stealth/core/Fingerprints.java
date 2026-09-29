package dev.stealth.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes finding fingerprints, which identify the same problem across runs. Every analyzer uses
 * this so they can't hash differently. See ADR-0001.
 */
public final class Fingerprints {

    private Fingerprints() {}

    /**
     * Returns {@code "v1:" + hex(sha256(ruleId + "\0" + key1 + "\0" + key2 ...))}.
     *
     * @param keys identity keys that survive unrelated edits: never line numbers or messages
     */
    public static String of(String ruleId, String... keys) {
        MessageDigest sha256 = sha256();
        sha256.update(ruleId.getBytes(StandardCharsets.UTF_8));
        for (String key : keys) {
            sha256.update((byte) 0);
            sha256.update(key.getBytes(StandardCharsets.UTF_8));
        }
        return "v1:" + HexFormat.of().formatHex(sha256.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
