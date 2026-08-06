package dev.pizzasmp.proxyguard;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gatekeeper between the Velocity proxy and its backends.
 *
 * The proxy is configured with try = ["smp", "limbo"], so by default a new player joining while the
 * SMP is down, and a player kicked or banned on the SMP, both fall through to the limbo. That is wrong
 * for those two cases but RIGHT for a maintenance shutdown (limbomaint deliberately drops connected
 * players into limbo to hold them). This plugin keeps the maintenance hold while fixing the other two:
 *
 *   - New join while the SMP is unreachable  -> deny login with a maintenance message (never limbo).
 *   - Failed connect to the SMP              -> disconnect with the maintenance message (never limbo).
 *   - Moderation kick / ban on the SMP       -> disconnect the player with that reason (never limbo).
 *   - Clean SMP shutdown / restart           -> fall through to limbo as before (the hold).
 *
 * The SMP-down / shutdown distinction is drawn from the kick reason text: known shutdown phrases are
 * treated as "let them fall to limbo", anything else is treated as a real punishment.
 */
public class PizzaProxyGuard {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private final AtomicBoolean smpUp = new AtomicBoolean(true);

    // Overridable via plugins/pizzaproxyguard/config.properties
    private String smpServerName = "smp";
    private String limboServerName = "limbo";
    private String maintenanceMessage = "&bThe server is currently restarting for maintenance.\n&7Please try connecting again in a few minutes.";
    // NOTE: must NOT include color codes — ban/kick messages are colored, and matching "§" here would
    // wrongly treat every punishment as a shutdown and send it to limbo instead of disconnecting.
    private String[] shutdownReasonNeedles = {
        "server closed", "shutdown", "shut down", "restart", "maintenance", "closed"
    };

    @Inject
    public PizzaProxyGuard(ProxyServer proxy, Logger logger, @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        this.loadConfig();
        // Poll the SMP backend so login decisions are instant (no per-login ping stall).
        this.proxy.getScheduler().buildTask(this, this::pollSmp)
            .delay(1, TimeUnit.SECONDS).repeat(3, TimeUnit.SECONDS).schedule();
        this.logger.info("PizzaProxyGuard active: SMP='{}', limbo='{}'.", this.smpServerName, this.limboServerName);
    }

    private void pollSmp() {
        RegisteredServer smp = this.proxy.getServer(this.smpServerName).orElse(null);
        if (smp == null) { this.smpUp.set(false); return; }
        smp.ping().whenComplete((ping, err) -> this.smpUp.set(err == null && ping != null));
    }

    @Subscribe
    public void onLogin(LoginEvent event) {
        // A fresh player always targets the SMP first (try order). If it is down, refuse entry here so
        // Velocity never falls them into the limbo lobby.
        if (!this.smpUp.get()) {
            event.setResult(ResultedEvent.ComponentResult.denied(this.maintenanceComponent()));
        }
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        String fromServer = event.getServer() != null ? event.getServer().getServerInfo().getName() : "";
        // Player was kicked mid-connect (the SMP refused / died during handshake): don't dump to limbo.
        if (event.kickedDuringServerConnect()) {
            if (this.smpServerName.equalsIgnoreCase(fromServer)) {
                event.setResult(KickedFromServerEvent.DisconnectPlayer.create(this.maintenanceComponent()));
            }
            return;
        }
        if (!this.smpServerName.equalsIgnoreCase(fromServer)) {
            return; // only mediate kicks originating from the SMP backend
        }
        Component reason = event.getServerKickReason().orElse(null);
        // Plain text (no color codes) so needle matching can't be fooled by formatting.
        String reasonText = reason == null ? ""
            : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(reason).toLowerCase(Locale.ROOT).trim();
        if (this.isShutdownReason(reasonText)) {
            // Clean shutdown / maintenance: preserve the limbomaint hold by redirecting to limbo.
            RegisteredServer limbo = this.proxy.getServer(this.limboServerName).orElse(null);
            if (limbo != null) {
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo));
            }
            return;
        }
        // A real moderation kick / ban reached the backend: disconnect the player with that reason
        // instead of letting them slip into limbo.
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
            reason != null ? reason : Component.text("You were removed from the server.")));
    }

    private boolean isShutdownReason(String reasonText) {
        if (reasonText.isEmpty()) return true; // no reason == backend simply went away
        for (String needle : this.shutdownReasonNeedles) {
            if (!needle.isEmpty() && reasonText.contains(needle)) return true;
        }
        return false;
    }

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private Component maintenanceComponent() {
        return AMP.deserialize(this.maintenanceMessage);
    }

    private void loadConfig() {
        try {
            File f = this.dataDir.resolve("config.properties").toFile();
            if (!f.isFile()) {
                // Write a starter config so it is easy to edit later.
                f.getParentFile().mkdirs();
                java.nio.file.Files.writeString(f.toPath(),
                    "# PizzaProxyGuard config\n"
                    + "smp-server=smp\n"
                    + "limbo-server=limbo\n"
                    + "# Use & color codes and \\n for newlines.\n"
                    + "maintenance-message=" + this.maintenanceMessage.replace("\n", "\\n") + "\n"
                    + "# Comma-separated substrings that mark a kick as a normal shutdown (fall to limbo).\n"
                    + "shutdown-reasons=server closed,shutdown,restart,maintenance,closed\n");
                return;
            }
            Properties props = new Properties();
            try (FileInputStream in = new FileInputStream(f)) { props.load(in); }
            this.smpServerName = props.getProperty("smp-server", this.smpServerName).trim();
            this.limboServerName = props.getProperty("limbo-server", this.limboServerName).trim();
            this.maintenanceMessage = props.getProperty("maintenance-message", this.maintenanceMessage).replace("\\n", "\n");
            String needles = props.getProperty("shutdown-reasons", "");
            if (!needles.isBlank()) {
                String[] parts = needles.toLowerCase(Locale.ROOT).split(",");
                for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
                this.shutdownReasonNeedles = parts;
            }
        } catch (Exception ex) {
            this.logger.warn("Failed reading config, using defaults: {}", ex.getMessage());
        }
    }
}
