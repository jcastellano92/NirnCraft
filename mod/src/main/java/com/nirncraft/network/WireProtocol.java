package com.nirncraft.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bump SCHEMA whenever an existing packet's fields, order, units or interpretation change. */
final class WireProtocol {
    // 3: NeoForge26.3 registry-aware payload codecs; 2: OpenDialogue enabled flags.
    static final int SCHEMA = 3;

    private WireProtocol() {}

    static String identity(String version, int schema, String registrations, int count) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(registrations.getBytes(StandardCharsets.UTF_8));
            return version + "-wire" + schema + "-" + HexFormat.of().formatHex(hash, 0, 16) + "-" + count;
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }
}
