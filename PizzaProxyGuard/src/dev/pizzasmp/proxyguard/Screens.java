package dev.pizzasmp.proxyguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * The disconnect screens this proxy shows, in one layout: the brand, a short title, what happened, what to do, and a
 * reference line (UTC time) that staff can match against the logs. Velocity's own messages are restyled the same way
 * through its lang/messages.properties override.
 */
final class Screens {
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter REF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private final String brand;
    private final TextColor accent;
    private final String support;

    Screens(Properties props) {
        this.brand = props.getProperty("screen.brand", "").trim();
        TextColor parsed = TextColor.fromHexString(props.getProperty("screen.color", "#00BFFF").trim());
        this.accent = parsed == null ? NamedTextColor.AQUA : parsed;
        this.support = props.getProperty("screen.support", "").trim();
    }

    Component busy() {
        return this.screen("Server busy", Component.text("A lot of players are joining right now, so we could not let you in yet."),
            "Please try again in a moment.");
    }

    Component starting() {
        return this.screen("Starting up", Component.text("The server is restarting and is not ready for players yet."),
            "Please try again in a moment.");
    }

    Component restarting() {
        return this.screen("Restarting", Component.text("The server is restarting."),
            "Please reconnect in a minute.");
    }

    Component dataUnavailable() {
        return this.screen("Player data unavailable", Component.text("We could not load your player data safely just now."),
            "Nothing was lost. Please try again in a moment.");
    }

    /** Maintenance: the operator's message (legacy & codes) as the body. */
    Component maintenance(String message) {
        return this.screen("Under maintenance", AMP.deserialize(message), "Please try again in a few minutes.");
    }

    Component unavailable(String server) {
        return this.screen("Connection lost", Component.text("We ran into a problem with " + server + "."),
            "Please try again shortly.");
    }

    Component removed() {
        return this.screen("Disconnected", Component.text("You were removed from the server."), null);
    }

    private Component screen(String title, Component body, String hint) {
        TextComponent.Builder b = Component.text();
        if (!this.brand.isEmpty()) {
            b.append(Component.text(this.brand, this.accent, TextDecoration.BOLD)).append(Component.newline()).append(Component.newline());
        }
        b.append(Component.text(title, NamedTextColor.WHITE)).append(Component.newline());
        b.append(body.colorIfAbsent(NamedTextColor.GRAY));
        if (hint != null) b.append(Component.newline()).append(Component.text(hint, NamedTextColor.GRAY));
        b.append(Component.newline()).append(Component.newline());
        if (!this.support.isEmpty()) b.append(Component.text(this.support, this.accent)).append(Component.newline());
        b.append(Component.text("Ref " + ZonedDateTime.now(ZoneOffset.UTC).format(REF), NamedTextColor.DARK_GRAY));
        return b.build();
    }
}
