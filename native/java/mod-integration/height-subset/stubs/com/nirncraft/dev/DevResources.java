package com.nirncraft.dev;

import java.io.IOException;
import java.io.InputStream;

/** harness stub: production behaviour, the packaged resource */
public final class DevResources {
    public static InputStream open(Class<?> owner, String path) throws IOException {
        return owner.getResourceAsStream(path);
    }
}
