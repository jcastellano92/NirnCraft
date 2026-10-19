package com.nirncraft.bake;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

/** harness stub: reads the shipped bake (hash header + deflated payload) without the inputs-hash staleness check */
public final class Bake {
    public static byte[] load(String name) {
        try (InputStream raw = Bake.class.getResourceAsStream("/nirncraft/baked/" + name + ".bin")) {
            if (raw == null) return null;
            DataInputStream in = new DataInputStream(raw);
            in.readUTF();
            return new InflaterInputStream(in).readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
