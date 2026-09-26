package dev.pizzasmp.tune;

import dev.pizzasmp.common.scheduler.PlatformScheduler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PizzaTune - runtime-mutable view distance and chunk send/load rates.
 * Feedback to players is via actionbar (hotbar message), not chat.
 */
@SuppressWarnings("deprecation")
public final class PizzaTune extends JavaPlugin {
    private static final String GLOBAL_CONFIG_CLASS = "io.papermc.paper.configuration.GlobalConfiguration";
    private boolean warnedUnsupportedChunkRates;

    @Override
    public void onEnable() {
        getCommand("viewdistance").setExecutor(this);
        getCommand("chunkspeeds").setExecutor(this);
        getLogger().info("PizzaTune ready. /viewdistance and /chunkspeeds are live-tunable.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String[] commandArgs = args.clone();

        if (sender instanceof Player player) {
            submitPlayerTask("command dispatch", player,
                () -> scheduleCommand(new CommandContext(sender, player, null, player.getName()),
                    commandName, commandArgs), null,
                reason -> getLogger().warning("Could not dispatch /" + commandName + " for a player: "
                    + reason + "."));
        } else if (sender instanceof BlockCommandSender blockSender) {
            Block block = blockSender.getBlock();
            Location blockLocation = block.getLocation().clone();
            CommandContext context = new CommandContext(sender, null, blockLocation, sender.getName());
            scheduleCommand(context, commandName, commandArgs);
        } else {
            CommandContext context = new CommandContext(sender, null, null, sender.getName());
            scheduleGlobalCommand(context, commandName, commandArgs);
        }
        return true;
    }

    private void scheduleCommand(CommandContext context, String commandName, String[] args) {
        scheduleGlobalCommand(context, commandName, args);
    }

    private void scheduleGlobalCommand(CommandContext context, String commandName, String[] args) {
        // World settings and Paper's global configuration are serialized on the global region.
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.globalNow(this,
                () -> executeCommand(context, commandName, args));
            if (!task.wasAccepted()) {
                reportCommandDispatchFailure(context, commandName, "the global scheduler rejected the task");
            }
        } catch (RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not schedule /" + commandName + " on the global region.", failure);
            reportCommandDispatchFailure(context, commandName, "the global scheduler is unavailable");
        }
    }

    private void reportCommandDispatchFailure(CommandContext context, String commandName, String reason) {
        if (context.player() == null && context.blockLocation() == null) {
            getLogger().warning("Could not dispatch /" + commandName + ": " + reason + ".");
            return;
        }
        feedback(context, ChatColor.RED, "Could not process /" + commandName + "; " + reason + ".");
    }

    private void executeCommand(CommandContext context, String commandName, String[] args) {
        switch (commandName) {
            case "viewdistance" -> handleViewDistance(context, args);
            case "chunkspeeds" -> handleChunkSpeeds(context, args);
            default -> { }
        }
    }

    // ---------- /viewdistance ----------

    private void handleViewDistance(CommandContext context, String[] args) {
        if (args.length == 0) {
            List<String> lines = new ArrayList<>();
            lines.add(ChatColor.AQUA + "Current view-distance per world:");
            for (World world : List.copyOf(Bukkit.getWorlds())) {
                lines.add(ChatColor.GRAY + "  " + world.getName()
                    + ChatColor.WHITE + " = " + world.getViewDistance()
                    + ChatColor.DARK_GRAY + "  (sim=" + world.getSimulationDistance() + ")");
            }
            lines.add(ChatColor.DARK_GRAY + "Usage: /viewdistance <2-32> | /viewdistance <world> <N>");
            sendLines(context, lines);
            return;
        }

        int distance;
        World targetWorld = null;
        if (args.length == 1) {
            Integer parsed = parseInt(args[0]);
            if (parsed == null) {
                feedback(context, ChatColor.RED, "view-distance: N must be a number 2-32");
                return;
            }
            distance = parsed;
        } else {
            targetWorld = Bukkit.getWorld(args[0]);
            if (targetWorld == null) {
                feedback(context, ChatColor.RED, "unknown world: " + args[0]);
                return;
            }
            Integer parsed = parseInt(args[1]);
            if (parsed == null) {
                feedback(context, ChatColor.RED, "view-distance: N must be a number 2-32");
                return;
            }
            distance = parsed;
        }
        if (distance < 2 || distance > 32) {
            feedback(context, ChatColor.RED, "view-distance: range 2-32");
            return;
        }

        String scope = targetWorld == null ? "all worlds, all players" : targetWorld.getName();
        String worldName = targetWorld == null ? null : targetWorld.getName();
        if (targetWorld == null) {
            for (World world : List.copyOf(Bukkit.getWorlds())) {
                world.setViewDistance(distance);
            }
        } else {
            targetWorld.setViewDistance(distance);
        }

        String actionbar = ChatColor.GREEN + "view-distance \u2192 " + distance
            + (targetWorld == null ? "" : " (" + targetWorld.getName() + ")");
        updatePlayersViewDistance(targetWorld, distance, actionbar,
            failures -> scheduleViewDistanceCompletion(context, distance, scope, worldName, failures));
    }

    private void scheduleViewDistanceCompletion(CommandContext context, int distance, String scope,
                                                String worldName, int failures) {
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.globalNow(this,
                () -> reportViewDistanceCompletion(context, distance, scope, worldName, failures));
            if (!task.wasAccepted()) {
                reportViewDistanceCompletionUnavailable(context, distance);
            }
        } catch (RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not schedule view-distance completion on the global region.",
                failure);
            reportViewDistanceCompletionUnavailable(context, distance);
        }
    }

    private void reportViewDistanceCompletion(CommandContext context, int distance, String scope,
                                              String worldName, int failures) {
        if (failures == 0) {
            feedback(context, ChatColor.GREEN, "view-distance \u2192 " + distance + " (" + scope + ")");
            getLogger().info("[PizzaTune] " + context.actorName() + " view-distance -> " + distance
                + (worldName == null ? " (all worlds)" : " (" + worldName + ")"));
            return;
        }

        feedback(context, ChatColor.RED, "World view-distance was set to " + distance
            + ", but " + failures + " player update task(s) did not complete.");
        getLogger().warning("[PizzaTune] " + context.actorName() + " changed world view-distance to " + distance
            + ", but " + failures + " player update task(s) did not complete.");
    }

    private void reportViewDistanceCompletionUnavailable(CommandContext context, int distance) {
        String message = "World view-distance was set to " + distance
            + ", but update completion could not be confirmed.";
        if (context.player() == null && context.blockLocation() == null) {
            getLogger().warning("[PizzaTune] " + message);
            return;
        }
        feedback(context, ChatColor.RED, message);
    }

    private void updatePlayersViewDistance(World targetWorld, int distance, String actionbar,
                                           IntConsumer completion) {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            completion.accept(0);
            return;
        }

        AtomicInteger remaining = new AtomicInteger(players.size());
        AtomicInteger failures = new AtomicInteger();
        Runnable completedOne = () -> {
            if (remaining.decrementAndGet() == 0) completion.accept(failures.get());
        };
        for (Player player : players) {
            submitPlayerTask("view-distance update", player, () -> {
                if (targetWorld == null || player.getWorld().equals(targetWorld)) {
                    player.setViewDistance(distance);
                }
                try {
                    if (isActionbarRecipient(player)) player.sendActionBar(actionbar);
                } catch (RuntimeException notificationFailure) {
                    getLogger().log(Level.FINE, "Could not deliver a view-distance actionbar notice.",
                        notificationFailure);
                }
            }, completedOne, reason -> {
                failures.incrementAndGet();
                getLogger().fine("A view-distance update could not be applied: " + reason + ".");
                completedOne.run();
            });
        }
    }

    // ---------- /chunkspeeds ----------

    private void handleChunkSpeeds(CommandContext context, String[] args) {
        if (args.length == 0) {
            showChunkSpeeds(context);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            Map<String, Double> rates = new LinkedHashMap<>();
            rates.put("send", -1.0);
            rates.put("load", -1.0);
            rates.put("generate", -1.0);
            if (applyChunkSpeeds(context, rates)) {
                feedback(context, ChatColor.GREEN, "chunkspeeds \u2192 unlimited");
                broadcastActionbar(ChatColor.GREEN + "chunkspeeds \u2192 unlimited");
            }
            return;
        }

        if (args.length == 1) {
            Double rate = parseDouble(args[0]);
            if (!isValidRate(rate)) {
                feedback(context, ChatColor.RED, "chunkspeeds: N must be a finite positive number (-1 = unlimited)");
                return;
            }
            Map<String, Double> rates = new LinkedHashMap<>();
            rates.put("send", rate);
            rates.put("load", rate);
            rates.put("generate", rate);
            if (applyChunkSpeeds(context, rates)) {
                feedback(context, ChatColor.GREEN, "chunkspeeds send/load/generate \u2192 " + fmt(rate));
                broadcastActionbar(ChatColor.GREEN + "chunkspeeds send/load/generate \u2192 " + fmt(rate));
            }
            return;
        }

        if (args.length == 2) {
            String key = args[0].toLowerCase(Locale.ROOT);
            if (!List.of("send", "load", "generate").contains(key)) {
                feedback(context, ChatColor.RED, "key must be send | load | generate");
                return;
            }
            Double rate = parseDouble(args[1]);
            if (!isValidRate(rate)) {
                feedback(context, ChatColor.RED, "chunkspeeds: N must be a finite positive number (-1 = unlimited)");
                return;
            }
            if (applyChunkSpeeds(context, Map.of(key, rate))) {
                feedback(context, ChatColor.GREEN,
                    "player-max-chunk-" + key + "-rate \u2192 " + fmt(rate));
                broadcastActionbar(ChatColor.GREEN + "chunkspeeds " + key + " \u2192 " + fmt(rate));
            }
            return;
        }

        feedback(context, ChatColor.GRAY,
            "Usage: /chunkspeeds <N> | /chunkspeeds <send|load|generate> <N> | /chunkspeeds reset");
    }

    private void showChunkSpeeds(CommandContext context) {
        PaperChunkRateSettings settings = resolveChunkRateSettings();
        if (settings == null) {
            feedback(context, ChatColor.RED, "Chunk-rate controls are unavailable on this server build.");
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.AQUA + "Current chunk rates (chunks/sec, -1 = unlimited):");
        lines.add(ChatColor.GRAY + "  player-max-chunk-send-rate " + ChatColor.WHITE + "= "
            + formatRate(readRate(settings, "send")));
        lines.add(ChatColor.GRAY + "  player-max-chunk-load-rate " + ChatColor.WHITE + "= "
            + formatRate(readRate(settings, "load")));
        lines.add(ChatColor.GRAY + "  player-max-chunk-generate-rate " + ChatColor.WHITE + "= "
            + formatRate(readRate(settings, "generate")));
        Integer concurrentLoads = readConcurrentChunkLoads(settings);
        lines.add(ChatColor.GRAY + "  player-max-concurrent-chunk-loads " + ChatColor.WHITE + "= "
            + (concurrentLoads == null ? "?" : concurrentLoads));
        lines.add(ChatColor.DARK_GRAY
            + "Usage: /chunkspeeds <N> | /chunkspeeds <key> <N> | /chunkspeeds reset");
        sendLines(context, lines);
    }

    private boolean applyChunkSpeeds(CommandContext context, Map<String, Double> rates) {
        PaperChunkRateSettings settings = resolveChunkRateSettings();
        if (settings == null) {
            feedback(context, ChatColor.RED, "Chunk-rate controls are unavailable on this server build.");
            return false;
        }

        List<RateChange> changes = new ArrayList<>();
        try {
            for (Map.Entry<String, Double> entry : rates.entrySet()) {
                Field field = settings.rateField(entry.getKey());
                changes.add(new RateChange(field, field.getDouble(settings.basic()), entry.getValue()));
            }

            int attempted = 0;
            try {
                for (RateChange change : changes) {
                    attempted++;
                    change.field().setDouble(settings.basic(), change.newValue());
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                for (int i = attempted - 1; i >= 0; i--) {
                    RateChange change = changes.get(i);
                    try {
                        change.field().setDouble(settings.basic(), change.previousValue());
                    } catch (ReflectiveOperationException | RuntimeException rollbackFailure) {
                        getLogger().severe("Unable to restore a chunk-rate setting after a failed update.");
                    }
                }
                throw failure;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            warnUnsupportedChunkRates();
            feedback(context, ChatColor.RED, "Chunk-rate controls are unavailable on this server build.");
            return false;
        }
    }

    // ---------- optional Paper GlobalConfiguration capability ----------

    private PaperChunkRateSettings resolveChunkRateSettings() {
        try {
            Class<?> globalType = Class.forName(GLOBAL_CONFIG_CLASS);
            Method getter = globalType.getMethod("get");
            if (!Modifier.isStatic(getter.getModifiers()) || !globalType.isAssignableFrom(getter.getReturnType())) {
                throw new NoSuchMethodException("Unsupported Paper GlobalConfiguration getter");
            }

            Object global = getter.invoke(null);
            if (global == null) throw new NoSuchFieldException("Paper GlobalConfiguration is not initialized");

            Object basic = requiredPublicValue(globalType, global, "chunkLoadingBasic");
            if (basic == null) throw new NoSuchFieldException("chunkLoadingBasic is unavailable");
            Field sendRate = requiredPublicField(basic.getClass(), "playerMaxChunkSendRate", double.class);
            Field loadRate = requiredPublicField(basic.getClass(), "playerMaxChunkLoadRate", double.class);
            Field generateRate = requiredPublicField(basic.getClass(), "playerMaxChunkGenerateRate", double.class);

            Object advanced = optionalPublicValue(globalType, global, "chunkLoadingAdvanced");
            Field concurrentLoads = advanced == null ? null
                : optionalPublicField(advanced.getClass(), "playerMaxConcurrentChunkLoads", int.class);
            return new PaperChunkRateSettings(basic, sendRate, loadRate, generateRate, advanced, concurrentLoads);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warnUnsupportedChunkRates();
            return null;
        }
    }

    private static Object requiredPublicValue(Class<?> ownerType, Object owner, String name)
        throws ReflectiveOperationException {
        Field field = ownerType.getField(name);
        if (Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
        return field.get(owner);
    }

    private static Object optionalPublicValue(Class<?> ownerType, Object owner, String name) {
        try {
            return requiredPublicValue(ownerType, owner, name);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Field requiredPublicField(Class<?> ownerType, String name, Class<?> expectedType)
        throws ReflectiveOperationException {
        Field field = ownerType.getField(name);
        if (Modifier.isStatic(field.getModifiers()) || field.getType() != expectedType) {
            throw new NoSuchFieldException(name);
        }
        return field;
    }

    private static Field optionalPublicField(Class<?> ownerType, String name, Class<?> expectedType) {
        try {
            return requiredPublicField(ownerType, name, expectedType);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private Double readRate(PaperChunkRateSettings settings, String key) {
        try {
            return settings.rateField(key).getDouble(settings.basic());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            warnUnsupportedChunkRates();
            return null;
        }
    }

    private Integer readConcurrentChunkLoads(PaperChunkRateSettings settings) {
        if (settings.advanced() == null || settings.concurrentLoadsField() == null) return null;
        try {
            return settings.concurrentLoadsField().getInt(settings.advanced());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            warnUnsupportedChunkRates();
            return null;
        }
    }

    private void warnUnsupportedChunkRates() {
        if (warnedUnsupportedChunkRates) return;
        warnedUnsupportedChunkRates = true;
        getLogger().warning("Paper chunk-rate configuration is unavailable or has an unsupported structure; "
            + "/chunkspeeds will fail closed.");
    }

    // ---------- feedback and scheduler ownership ----------

    private void feedback(CommandContext context, ChatColor color, String text) {
        String message = color + text;
        if (context.player() != null) {
            Player player = context.player();
            submitPlayerTask("command feedback", player, () -> player.sendActionBar(message), null,
                reason -> getLogger().fine("Could not deliver player command feedback: " + reason + "."));
        } else if (context.blockLocation() != null) {
            submitBlockTask(context, () -> context.sender().sendMessage(message), "command feedback");
        } else {
            context.sender().sendMessage(message);
        }
    }

    private void sendLines(CommandContext context, List<String> lines) {
        if (context.player() != null) {
            Player player = context.player();
            submitPlayerTask("command output", player, () -> lines.forEach(player::sendMessage), null,
                reason -> getLogger().fine("Could not deliver player command output: " + reason + "."));
        } else if (context.blockLocation() != null) {
            submitBlockTask(context, () -> lines.forEach(context.sender()::sendMessage), "command output");
        } else {
            lines.forEach(context.sender()::sendMessage);
        }
    }

    private void broadcastActionbar(String line) {
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) return;

        AtomicInteger remaining = new AtomicInteger(players.size());
        AtomicInteger failures = new AtomicInteger();
        Runnable completedOne = () -> {
            if (remaining.decrementAndGet() == 0 && failures.get() > 0) {
                getLogger().fine("A chunk-rate actionbar notice could not be delivered to "
                    + failures.get() + " player(s).");
            }
        };
        for (Player player : players) {
            submitPlayerTask("chunk-rate actionbar", player, () -> {
                if (isActionbarRecipient(player)) player.sendActionBar(line);
            }, completedOne, reason -> {
                failures.incrementAndGet();
                completedOne.run();
            });
        }
    }

    private void submitBlockTask(CommandContext context, Runnable action, String operation) {
        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.regionNow(this, context.blockLocation(), action);
            if (!task.wasAccepted()) {
                getLogger().warning("Could not deliver " + operation + ": the command block's region scheduler "
                    + "rejected the task.");
            }
        } catch (RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not schedule " + operation
                + " on the command block's region.", failure);
        }
    }

    private void submitPlayerTask(String operation, Player player, Runnable action,
                                  Runnable completed, Consumer<String> failed) {
        AtomicBoolean settled = new AtomicBoolean();
        Consumer<String> failOnce = reason -> {
            if (settled.compareAndSet(false, true) && failed != null) failed.accept(reason);
        };

        try {
            PlatformScheduler.TaskHandle task = PlatformScheduler.entityNow(this, player, () -> {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    if (settled.compareAndSet(false, true)) {
                        getLogger().log(Level.WARNING, "PizzaTune " + operation + " failed on a player scheduler.",
                            failure);
                        if (failed != null) failed.accept("the player task failed");
                    }
                    return;
                }
                if (settled.compareAndSet(false, true) && completed != null) completed.run();
            }, () -> failOnce.accept("the entity retired before the task ran"));
            if (!task.wasAccepted()) failOnce.accept("the entity scheduler rejected the task");
        } catch (RuntimeException failure) {
            if (settled.compareAndSet(false, true)) {
                getLogger().log(Level.WARNING, "Could not schedule PizzaTune " + operation
                    + " on a player scheduler.", failure);
                if (failed != null) failed.accept("the player scheduler is unavailable");
            }
        }
    }

    private static boolean isActionbarRecipient(Player player) {
        return player.isOp() || player.hasPermission("pizzasmp.tune.viewdistance")
            || player.hasPermission("pizzasmp.tune.chunkspeeds");
    }

    // ---------- helpers ----------

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isValidRate(Double rate) {
        return rate != null && Double.isFinite(rate) && (rate == -1.0 || rate > 0.0);
    }

    private static String fmt(double value) {
        if (!Double.isFinite(value)) return String.valueOf(value);
        return value == (long) value ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String formatRate(Double value) {
        return value == null ? "?" : fmt(value);
    }

    private record CommandContext(CommandSender sender, Player player, Location blockLocation, String actorName) { }

    private record RateChange(Field field, double previousValue, double newValue) { }

    private record PaperChunkRateSettings(Object basic, Field sendRateField, Field loadRateField,
                                          Field generateRateField, Object advanced,
                                          Field concurrentLoadsField) {
        private Field rateField(String key) throws NoSuchFieldException {
            return switch (key) {
                case "send" -> sendRateField;
                case "load" -> loadRateField;
                case "generate" -> generateRateField;
                default -> throw new NoSuchFieldException(key);
            };
        }
    }
}
