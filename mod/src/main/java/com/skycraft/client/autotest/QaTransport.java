package com.skycraft.client.autotest;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Opt-in real TCP diagnostics against an explicitly selected loopback QA server. */
final class QaTransport {
    private QaTransport() {}

    static String loopbackAddress(String address) {
        if (!address.matches("127\\.0\\.0\\.1:[0-9]{1,5}"))
            throw new IllegalArgumentException("QA server must be an explicit 127.0.0.1:PORT loopback address");
        int port = Integer.parseInt(address.substring(address.indexOf(':') + 1));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("QA server port is outside 1..65535");
        return address;
    }

    static void connect(Minecraft mc, Screen parent, String address) {
        address = loopbackAddress(address);
        ConnectScreen.startConnecting(parent, mc, ServerAddress.parseString(address),
                new ServerData("Skycraft native TCP QA", address, ServerData.Type.OTHER), false, null);
    }

    static boolean connected(Minecraft mc) {
        var listener = mc.getConnection();
        return listener != null && listener.getConnection().isConnected();
    }

    static boolean remote(Minecraft mc) {
        return connected(mc) && !mc.getConnection().getConnection().isMemoryConnection();
    }

    static boolean operatorReady(Minecraft mc) {
        return mc.player != null && mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    static void describe(Minecraft mc, JsonObject output) {
        var listener = mc.getConnection();
        boolean connected = connected(mc);
        output.addProperty("connected", connected);
        output.addProperty("integratedServer", mc.getSingleplayerServer() != null);
        output.addProperty("remote", remote(mc));
        if (listener == null) {
            output.add("isMemoryConnection", JsonNull.INSTANCE);
            output.addProperty("transport", "disconnected");
        } else {
            var connection = listener.getConnection();
            output.addProperty("isMemoryConnection", connection.isMemoryConnection());
            output.addProperty("transport", connection.isMemoryConnection() ? "memory" : "tcp");
            if (connection.getRemoteAddress() != null)
                output.addProperty("remoteAddress", connection.getRemoteAddress().toString());
        }
    }
}
