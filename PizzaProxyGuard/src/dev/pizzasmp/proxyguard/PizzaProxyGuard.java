package dev.pizzasmp.proxyguard;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.Player;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Cross-server chat relay, absorbed from the old standalone PizzaVelocityBridge plugin.
     * Velocity allows one @Plugin per jar, so it lives here as a component this class
     * constructs and registers. See ChatRelay for what it handles.
     */
    private ChatRelay chatRelay;

    /** Backends currently flagged in maintenance_state, refreshed on a timer. */
    private volatile Set<String> maintenanceUnits = Set.of();
    /** Tier 3. Units flagged "!name" in targets_csv: evict rather than hold. */
    private volatile Set<String> emergencyUnits = Set.of();
    /** Players already sent to the hold server. The poll runs every 5s and a connect is
     *  not instant, so without this it re-fires and Velocity answers "you are already
     *  trying to connect to this server". */
    private final Set<java.util.UUID> relocating = ConcurrentHashMap.newKeySet();
    /** Tier 2: whole-network maintenance. Nobody joins at all. */
    private volatile boolean networkMaintenance = false;
    private volatile String maintenanceMotd = "";

    // Dev backend is restricted. Anyone without one of these is told it does not exist.
    private String devServerName = "dev";
    private String[] devAllowedPermissions = { "pizzasmp.dev.access", "group.dev", "group.admin", "group.sradmin" };
    private String devDenyMessage =
        "&fWe don't know what happened here, but it looks like you attempted to connect to a server that doesn't exist";
    private String emergencyMessage = "&cWe've encountered an issue while trying to connect you to {server}";
    /** Datacentre/region code shown in player-facing process names (goliath2-NY). */
    private String regionCode = "NY";

    // Overridable via plugins/pizzaproxyguard/config.properties
    private String smpServerName = "survival";
    private String limboServerName = "lobby";
    private String maintenanceMessage = "&fWe are under maintenance, please wait a few minutes and try again";
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
        // Maintenance is gated HERE, at the proxy, so a player never opens a connection
        // to a backend that is down. Gating on the backend only works while the backend
        // is up, which during maintenance is exactly what it is not.
        this.proxy.getScheduler().buildTask(this, this::pollMaintenance)
            .delay(2, TimeUnit.SECONDS).repeat(5, TimeUnit.SECONDS).schedule();
        // Bring up the chat relay and register it as a listener in its own right so its
        // @Subscribe methods fire. It needs `this` because Velocity keys scheduled tasks
        // and channel registration on the registered plugin instance, not on the helper.
        this.chatRelay = new ChatRelay(this, this.proxy, this.logger, this.dataDir);
        this.proxy.getEventManager().register(this, this.chatRelay);
        this.chatRelay.onProxyInit(event);
        this.logger.info("PizzaProxyGuard active: backend='{}', fallback='{}', chat relay on.",
            this.smpServerName, this.limboServerName);
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
        // Tier 2 (whole-network maintenance): refuse entry outright. Repeatedly kicking
        // players as each restart lands is a worse experience than a closed door.
        if (this.networkMaintenance) {
            event.setResult(ResultedEvent.ComponentResult.denied(this.maintenanceComponent()));
            return;
        }
        // Deliberately NOT denying just because the gameplay backend is down. Velocity's
        // try=["lobby"] puts the player in lobby, and PNC's reconnect routing forwards
        // them on when their last server returns. Denying here locked players out of a
        // perfectly healthy lobby every time survival restarted.
        // A player is only refused when there is genuinely nowhere to put them.
        if (this.noBackendAvailable()) {
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

    /** Refresh maintenance state from the shared DB. targets_csv is a generic list of
     *  unit names, so under distributed Folia these become process/slice ids unchanged. */
    private void pollMaintenance() {
        if (this.chatRelay == null) return;
        try (Connection c = this.chatRelay.openConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT active, targets_csv, message FROM maintenance_state WHERE id=1 LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) { this.maintenanceUnits = Set.of(); this.networkMaintenance = false; return; }
            boolean active = rs.getBoolean(1);
            String csv = rs.getString(2);
            this.maintenanceMotd = rs.getString(3) == null ? "" : rs.getString(3);
            Set<String> units = new HashSet<>();
            Set<String> emergency = new HashSet<>();
            if (active && csv != null) {
                for (String part : csv.split(",")) {
                    String u = part.trim().toLowerCase(Locale.ROOT);
                    if (u.isEmpty()) continue;
                    // "!name" marks Tier 3: evict, do not hold.
                    if (u.startsWith("!")) { u = u.substring(1); emergency.add(u); }
                    units.add(u);
                }
            }
            this.emergencyUnits = Set.copyOf(emergency);
            // "*" (or active with no targets) means the WHOLE network is closed — Tier 2.
            this.networkMaintenance = active && (units.contains("*") || units.isEmpty());
            this.maintenanceUnits = Set.copyOf(units);
            this.evictFromEmergencyUnits();
            this.relocateFromMaintenanceUnits();
        } catch (Exception ex) {
            // Fail OPEN: a DB blip must not lock everyone out of a healthy network.
            this.logger.warn("Could not read maintenance_state: {}", ex.getMessage());
        }
    }

    /** Disconnect anyone currently on an emergency-closed backend. Runs on the same
     *  poll, so eviction follows the flag within one interval. */
    private void evictFromEmergencyUnits() {
        if (this.emergencyUnits.isEmpty()) return;
        for (Player p : this.proxy.getAllPlayers()) {
            String cur = p.getCurrentServer()
                .map(c -> c.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse("");
            if (this.emergencyUnits.contains(cur)) {
                p.disconnect(this.emergencyComponent(cur));
            }
        }
    }

    /**
     * Move players off a backend that has just been flagged for ordinary maintenance.
     *
     * Without this, backend_maint.sh flagged the unit and then stopped the server, and
     * players were simply DISCONNECTED by the shutdown — the reported symptom. The whole
     * point of Tier 1 is that they are held and returned, so the proxy relocates them to
     * the maintenance backend as soon as the flag appears. Emergency units are skipped:
     * those are evicted by evictFromEmergencyUnits(), not held.
     */
    private void relocateFromMaintenanceUnits() {
        if (this.maintenanceUnits.isEmpty()) return;
        RegisteredServer hold = this.proxy.getServer("maintenance")
            .or(() -> this.proxy.getServer(this.limboServerName)).orElse(null);
        if (hold == null) return;
        String holdName = hold.getServerInfo().getName().toLowerCase(Locale.ROOT);
        if (this.maintenanceUnits.contains(holdName)) return;  // hold target itself is down

        for (Player p : this.proxy.getAllPlayers()) {
            String cur = p.getCurrentServer()
                .map(c -> c.getServerInfo().getName().toLowerCase(Locale.ROOT)).orElse("");
            if (cur.isEmpty() || !this.maintenanceUnits.contains(cur)) continue;
            if (this.emergencyUnits.contains(cur)) continue;   // handled as an eviction
            if (!this.relocating.add(p.getUniqueId())) continue; // already in flight
            p.sendMessage(this.maintenanceComponent());
            p.createConnectionRequest(hold).connect()
                .whenComplete((res, err) -> this.relocating.remove(p.getUniqueId()));
        }
    }

    @Subscribe
    public void onRelocated(com.velocitypowered.api.event.player.ServerConnectedEvent event) {
        this.relocating.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onLeave(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        this.relocating.remove(event.getPlayer().getUniqueId());
    }

    /** True when every registered backend is either down or flagged - nowhere to land. */
    private boolean noBackendAvailable() {
        for (RegisteredServer rs : this.proxy.getAllServers()) {
            String n = rs.getServerInfo().getName().toLowerCase(Locale.ROOT);
            if (n.equals(this.devServerName)) continue;      // dev is never a landing spot
            if (this.maintenanceUnits.contains(n)) continue; // flagged
            return false;                                    // at least one is usable
        }
        return true;
    }

    private boolean isUnderMaintenance(String server) {
        return server != null && this.maintenanceUnits.contains(server.toLowerCase(Locale.ROOT));
    }

    /** Dev is restricted; everyone else is told it does not exist rather than that they
     *  lack permission, so an unprivileged player learns nothing about the backend. */
    private boolean mayUseDev(Player player) {
        for (String node : this.devAllowedPermissions) {
            if (player.hasPermission(node)) return true;
        }
        return false;
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String target = event.getOriginalServer().getServerInfo().getName().toLowerCase(Locale.ROOT);

        if (this.devServerName.equalsIgnoreCase(target) && !this.mayUseDev(player)) {
            player.sendMessage(AMP.deserialize(this.devDenyMessage));
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }
        if (this.isUnderMaintenance(target)) {
            player.sendMessage(this.maintenanceComponent());
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
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
        String msg = this.maintenanceMotd == null || this.maintenanceMotd.isBlank()
            ? this.maintenanceMessage : this.maintenanceMotd;
        return AMP.deserialize(msg);
    }

    /**
     * Player-facing name for a backend: "goliath<id>-<REGION>", e.g. goliath2-NY.
     *
     * Players see the process identity, not our internal backend name — the same scheme
     * used in each server's shutdown-message. Under distributed Folia these ids become
     * per-process/per-slice, so the message keeps identifying exactly what a player was
     * connected to. Region is fixed to NY for now; make it config when there is a second.
     */
    private String prettyServerName(String name) {
        if (name == null || name.isBlank()) return "the server";
        String key = name.toLowerCase(Locale.ROOT);
        int id = switch (key) {
            case "lobby" -> 1;
            case "survival" -> 2;
            case "dev" -> 3;
            case "maintenance" -> 4;
            default -> 0;
        };
        return "goliath" + id + "-" + this.regionCode;
    }

    /** Tier 3 emergency disconnect text. */
    private Component emergencyComponent(String server) {
        return AMP.deserialize(this.emergencyMessage.replace("{server}", prettyServerName(server)));
    }

    private void loadConfig() {
        try {
            File f = this.dataDir.resolve("config.properties").toFile();
            if (!f.isFile()) {
                // Write a starter config so it is easy to edit later.
                f.getParentFile().mkdirs();
                java.nio.file.Files.writeString(f.toPath(),
                    "# PizzaProxyGuard config\n"
                    + "smp-server=survival\n"
                    + "limbo-server=lobby\n"
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
