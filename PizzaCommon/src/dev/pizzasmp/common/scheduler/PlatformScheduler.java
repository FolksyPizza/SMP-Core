package dev.pizzasmp.common.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Scheduler entry points shared by the plugins and supported by Paper and Folia. */
public final class PlatformScheduler {
    private static final long MILLIS_PER_TICK = 50L;
    private static final TaskHandle CANCELLED = new TaskHandle() {
        @Override
        public void cancel() { }

        @Override
        public boolean wasAccepted() { return false; }
    };

    private PlatformScheduler() { }

    public static TaskHandle entityNow(Plugin plugin, Entity entity, Runnable action, Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        return handle(entity.getScheduler().run(plugin, task -> action.run(), retired));
    }

    public static TaskHandle entityLater(Plugin plugin, Entity entity, Runnable action, Runnable retired, long delayTicks) {
        Objects.requireNonNull(entity, "entity");
        if (delayTicks <= 0L) return entityNow(plugin, entity, action, retired);
        return handle(entity.getScheduler().runDelayed(plugin, task -> action.run(), retired, delayTicks));
    }

    public static TaskHandle entityRepeating(Plugin plugin, Entity entity, Runnable action, Runnable retired,
                                             long initialDelayTicks, long periodTicks) {
        Objects.requireNonNull(entity, "entity");
        return handle(entity.getScheduler().runAtFixedRate(plugin, task -> action.run(), retired,
            Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    public static TaskHandle regionNow(Plugin plugin, Location location, Runnable action) {
        return handle(Bukkit.getRegionScheduler().run(plugin, Objects.requireNonNull(location, "location"), task -> action.run()));
    }

    public static TaskHandle regionLater(Plugin plugin, Location location, Runnable action, long delayTicks) {
        if (delayTicks <= 0L) return regionNow(plugin, location, action);
        return handle(Bukkit.getRegionScheduler().runDelayed(plugin, Objects.requireNonNull(location, "location"),
            task -> action.run(), delayTicks));
    }

    public static TaskHandle regionRepeating(Plugin plugin, Location location, Runnable action,
                                             long initialDelayTicks, long periodTicks) {
        return handle(Bukkit.getRegionScheduler().runAtFixedRate(plugin, Objects.requireNonNull(location, "location"),
            task -> action.run(), Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    public static TaskHandle globalNow(Plugin plugin, Runnable action) {
        return handle(Bukkit.getGlobalRegionScheduler().run(plugin, task -> action.run()));
    }

    public static TaskHandle globalLater(Plugin plugin, Runnable action, long delayTicks) {
        if (delayTicks <= 0L) return globalNow(plugin, action);
        return handle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> action.run(), delayTicks));
    }

    public static TaskHandle globalRepeating(Plugin plugin, Runnable action, long initialDelayTicks, long periodTicks) {
        return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> action.run(),
            Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    public static TaskHandle asyncNow(Plugin plugin, Runnable action) {
        return handle(Bukkit.getAsyncScheduler().runNow(plugin, task -> action.run()));
    }

    public static TaskHandle asyncLater(Plugin plugin, Runnable action, long delayTicks) {
        return handle(Bukkit.getAsyncScheduler().runDelayed(plugin, task -> action.run(),
            ticksToMillis(Math.max(1L, delayTicks)), TimeUnit.MILLISECONDS));
    }

    public static TaskHandle asyncRepeating(Plugin plugin, Runnable action, long initialDelayTicks, long periodTicks) {
        return handle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> action.run(),
            ticksToMillis(Math.max(1L, initialDelayTicks)), ticksToMillis(Math.max(1L, periodTicks)),
            TimeUnit.MILLISECONDS));
    }

    private static long ticksToMillis(long ticks) {
        if (ticks > Long.MAX_VALUE / MILLIS_PER_TICK) return Long.MAX_VALUE;
        return ticks * MILLIS_PER_TICK;
    }

    private static TaskHandle handle(ScheduledTask task) {
        return task == null ? CANCELLED : task::cancel;
    }

    @FunctionalInterface
    public interface TaskHandle {
        void cancel();

        /** Returns whether the scheduler accepted this task. Existing handles represent accepted tasks. */
        default boolean wasAccepted() { return true; }
    }
}
