package com.nirncraft.client.screen.title;

import com.nirncraft.inventory.client.SkyUi;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Local network joining instructions and the host's actual published address, without a relay service. */
public final class SkyLanHelpScreen extends Screen {
    private static final List<String> INSTRUCTIONS = List.of(
            "Host: pause your world and choose World & Multiplayer.",
            "Enable multiplayer access for your local network and apply.",
            "Join: use Multiplayer; nearby open worlds appear automatically.",
            "If discovery misses it, use Direct Connection with the host's address.",
            "Use the same NirnCraft version on every computer.");
    private final Screen parent;
    private List<String> addresses = List.of();
    private boolean scanning = true;
    private int scanGeneration;

    public SkyLanHelpScreen(Screen parent) {
        super(Component.literal("Playing Together"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int generation = ++scanGeneration;
        addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(width / 2 - 70, height - 30, 140, 20).build());
        var server = minecraft.getSingleplayerServer();
        if (server == null || !server.isPublished()) {
            scanning = false;
            return;
        }
        int port = server.getPort();
        scanning = true;
        CompletableFuture.supplyAsync(() -> localAddresses(port)).thenAccept(found -> minecraft.execute(() -> {
            if (scanGeneration != generation || minecraft.gui.screen() != this || minecraft.getSingleplayerServer() != server || !server.isPublished()
                    || server.getPort() != port) return;
            addresses = found;
            scanning = false;
            int y = instructionsBottom() + 4 + 2 * font.lineHeight + 6;
            for (String address : addresses) {
                if (y + 20 >= height - 40) break;
                addRenderableWidget(Button.builder(Component.literal("Copy " + address), button -> {
                    minecraft.keyboardHandler.setClipboard(address);
                    button.setMessage(Component.literal("Copied " + address));
                }).bounds(width / 2 - 140, y, 280, 20).build());
                y += 24;
            }
        }));
    }

    private int instructionsBottom() {
        int y = 48, wrapWidth = Math.min(340, width - 24);
        for (String text : INSTRUCTIONS) y += font.split(Component.literal(text), wrapWidth).size() * font.lineHeight + 4;
        return y;
    }

    private static List<String> localAddresses(int port) {
        List<String> result = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                var addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    var address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        String endpoint = address.getHostAddress() + ":" + port;
                        if (!result.contains(endpoint)) result.add(endpoint);
                    }
                }
            }
        } catch (java.net.SocketException unavailable) {
            // Instructions still work when OS address enumeration is unavailable.
        }
        result.sort(Comparator.naturalOrder());
        return List.copyOf(result);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int left = Math.max(12, width / 2 - 170), y = 24;
        g.centeredText(font, getTitle(), width / 2, y, SkyUi.GOLD);
        y += 24;
        for (String text : INSTRUCTIONS) {
            y += g.textWithWordWrap(font, Component.literal(text), left, y, Math.min(340, width - 24), SkyUi.TEXT) + 4;
        }
        var server = minecraft.getSingleplayerServer();
        String state = server == null || !server.isPublished() ? "This computer is not hosting an open LAN world."
                : scanning ? "Reading local network addresses..."
                : addresses.isEmpty() ? "Find the host's local address in your operating system's network settings."
                : "Share the address for the network both computers use:";
        g.textWithWordWrap(font, Component.literal(state), left, y + 2, Math.min(340, width - 24), SkyUi.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
