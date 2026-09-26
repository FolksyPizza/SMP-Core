package dev.pizzasmp.plugins;

import dev.pizzasmp.common.scheduler.PlatformScheduler;
import dev.pizzasmp.common.scheduler.PlatformScheduler.TaskHandle;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Replaces the vanilla 9x3 (27-slot) ender chest with a 9x6 (54-slot) virtual inventory that is
 * persisted per player to a gzipped .dat file. Any way of opening an ender chest — the block, the
 * inventory-open event, or /ec | /enderchest | /endersee &lt;player&gt; — is redirected to the
 * expanded inventory.
 *
 * <p>The original source for this plugin was unavailable; this implementation was reconstructed
 * from the compiled 1.0.0 jar.
 *
 * <p>Inspection uses an exclusive target lease and a separate viewer-owned inventory. The lease
 * prevents the target from changing their chest while the viewer edits the proxy. A controlled
 * plugin disable still needs a host-side drain while this plugin is enabled: onDisable logs active
 * work but deliberately does not read or close inventories outside their entity contexts.
 */
public final class EnderchestExpander extends JavaPlugin implements Listener {

    private static final long AUTOSAVE_INITIAL_DELAY_TICKS = 6000L;
    private static final long AUTOSAVE_PERIOD_TICKS = 6000L;

    private final ConcurrentMap<UUID, ChestState> chests = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, IoLane> ioLanes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, InspectionRequest> inspectionRequests = new ConcurrentHashMap<>();
    private final AtomicLong inspectionTokens = new AtomicLong();
    private Path enderchestFolder;
    private volatile boolean acceptingInspections = true;
    private volatile boolean acceptingIo = true;
    // Prevents a submission racing disable/re-enable from becoming an orphaned lane head.
    private volatile long ioEpoch;

    @Override
    public void onEnable() {
        this.acceptingInspections = true;
        this.acceptingIo = true;
        this.enderchestFolder = this.getDataFolder().toPath().resolve("enderchests");
        try {
            Files.createDirectories(this.enderchestFolder);
        } catch (IOException ex) {
            this.getLogger().severe("Could not create the expanded enderchest data folder.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.getServer().getPluginManager().registerEvents(this, this);
        this.getLogger().info("EnderchestExpander enabled - enderchests now 9x6!");
    }

    @Override
    public void onDisable() {
        this.acceptingInspections = false;
        this.acceptingIo = false;
        this.ioEpoch++;
        this.stopIoForShutdown();
        int activeInspections = this.inspectionRequests.size();
        int queuedIo = this.pendingIoCount();
        int cachedStates = this.chests.size();
        if (activeInspections > 0 || queuedIo > 0 || cachedStates > 0) {
            this.getLogger().warning("EnderchestExpander is disabling with " + activeInspections
                    + " inspection request(s), " + cachedStates + " cached chest state(s), and " + queuedIo
                    + " queued file operation(s). The host must close and snapshot inventories on their entity owners, "
                    + "then drain saves before disabling; no host drain hook is wired yet, and this hook does not access players.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        String[] parts = message.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        boolean self = command.equals("/ec") || command.equals("/enderchest");
        boolean endersee = command.equals("/endersee");
        if (!self && !endersee) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (endersee) {
            if (parts.length == 1 || parts[1].isBlank()) {
                sendActionBar(player, "Player not found or offline.", NamedTextColor.RED);
                return;
            }
            this.openInspection(player, parts[1].trim());
            return;
        }
        this.openOwnEnderchest(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Inventory inventory = event.getInventory();
            if (!(inventory.getHolder() instanceof DoubleChestInventory)
                    && inventory.getType() == InventoryType.ENDER_CHEST) {
                event.setCancelled(true);
                this.openOwnEnderchest(player);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        Inventory closed = event.getInventory();
        if (closed.getHolder() instanceof InspectionHolder holder) {
            InspectionRequest request = this.inspectionRequests.get(player.getUniqueId());
            InspectionLease lease = request == null ? null : request.lease.get();
            if (lease != null && lease.token == holder.token && lease.viewerId.equals(player.getUniqueId())
                    && lease.proxyInventory == closed) {
                this.finishInspectionFromViewer(lease, closed);
            }
            return;
        }

        ChestState state = this.chests.get(player.getUniqueId());
        if (state != null && state.inventory == closed) {
            this.captureOwnerInventory(player.getUniqueId(), state, closed).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.logSaveFailure(error);
                }
            });
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        InspectionRequest viewerRequest = this.inspectionRequests.get(uuid);
        if (viewerRequest != null) {
            viewerRequest.cancelled.set(true);
            InspectionLease lease = viewerRequest.lease.get();
            if (lease != null && !lease.finalizing.get()) {
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top == lease.proxyInventory && top != null) {
                    this.finishInspectionFromViewer(lease, top);
                    player.closeInventory();
                } else {
                    this.finishInspectionFromSnapshot(lease);
                }
            }
        }

        ChestState state = this.chests.get(uuid);
        if (state == null) {
            return;
        }

        state.onlinePlayer.compareAndSet(player, null);
        InspectionLease targetLease = state.activeLease.get();
        if (targetLease != null) {
            this.requestViewerClose(targetLease);
            return;
        }

        if (state.autosave != null) {
            state.autosave.cancel();
            state.autosave = null;
        }

        if (state.loading && state.snapshot.get() == null) {
            this.retireLoad(uuid, state);
            this.removeStateIfOffline(uuid, state);
            return;
        }

        CompletableFuture<Void> save;
        if (state.inventory != null) {
            save = this.captureOwnerInventory(uuid, state, state.inventory);
            state.inventory = null;
        } else {
            save = this.persistLatestSnapshot(uuid, state);
        }
        if (save == null) {
            this.removeStateIfOffline(uuid, state);
            return;
        }
        save.whenComplete((ignored, error) -> {
            if (error != null) {
                this.logSaveFailure(error);
                return;
            }
            this.removeStateIfOffline(uuid, state);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ChestState state = this.chests.compute(uuid, (ignored, current) -> {
            ChestState selected = current == null ? new ChestState() : current;
            selected.onlinePlayer.set(player);
            return selected;
        });
        if (state.activeLease.get() != null) {
            return;
        }
        this.withExpandedEnderchest(player, ignored -> { }, () -> { });
    }

    private void openOwnEnderchest(Player player) {
        ChestState state = this.chests.get(player.getUniqueId());
        if (state != null && state.activeLease.get() != null) {
            sendActionBar(player, "This enderchest is being inspected.", NamedTextColor.RED);
            return;
        }

        InspectionRequest viewerRequest = this.inspectionRequests.get(player.getUniqueId());
        if (viewerRequest != null) {
            viewerRequest.cancelled.set(true);
            InspectionLease lease = viewerRequest.lease.get();
            if (lease != null) {
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top == lease.proxyInventory && top != null) {
                    player.closeInventory();
                }
            }
        }

        this.withExpandedEnderchest(player, inventory -> {
            ChestState current = this.chests.get(player.getUniqueId());
            if (current != null && current.activeLease.get() != null) {
                sendActionBar(player, "This enderchest is being inspected.", NamedTextColor.RED);
                return;
            }
            player.openInventory(inventory);
        }, () -> sendActionBar(player, "Your enderchest could not be loaded.", NamedTextColor.RED));
    }

    private void openInspection(Player viewer, String targetName) {
        if (!this.acceptingInspections) {
            sendActionBar(viewer, "Enderchest inspections are unavailable right now.", NamedTextColor.RED);
            return;
        }

        UUID viewerId = viewer.getUniqueId();
        InspectionRequest request = new InspectionRequest(viewer);
        InspectionRequest existing = this.inspectionRequests.putIfAbsent(viewerId, request);
        if (existing != null) {
            sendActionBar(viewer, "Close your current inspection before opening another.", NamedTextColor.RED);
            return;
        }

        try {
            TaskHandle lookup = PlatformScheduler.globalNow(this, () -> {
                Player target = Bukkit.getPlayerExact(targetName);
                if (target == null) {
                    this.failInspectionRequest(request, "Player not found or offline.");
                    return;
                }

                this.runOnPlayer(target, targetPlayer -> {
                    if (targetPlayer.getUniqueId().equals(viewerId)) {
                        this.inspectionRequests.remove(viewerId, request);
                        this.openOwnEnderchest(viewer);
                        return;
                    }
                    this.withExpandedEnderchest(targetPlayer,
                            ignored -> this.beginInspection(targetPlayer, request),
                            () -> this.failInspectionRequest(request, "Player not found or offline."));
                }, () -> this.failInspectionRequest(request, "Player not found or offline."));
            });
            if (!lookup.wasAccepted()) {
                this.failInspectionRequest(request, "Player not found or offline.");
            }
        } catch (RuntimeException ex) {
            this.failInspectionRequest(request, "Player not found or offline.");
        }
    }

    private void beginInspection(Player target, InspectionRequest request) {
        if (!this.acceptingInspections || request.cancelled.get()
                || this.inspectionRequests.get(request.viewerId) != request) {
            this.failInspectionRequest(request, null);
            return;
        }

        UUID targetId = target.getUniqueId();
        ChestState state = this.chests.get(targetId);
        if (state == null || state.inventory == null) {
            this.failInspectionRequest(request, "That enderchest is not ready yet.");
            return;
        }

        InspectionLease lease = new InspectionLease(this.inspectionTokens.incrementAndGet(), request, targetId, state);
        if (!state.activeLease.compareAndSet(null, lease)) {
            this.failInspectionRequest(request, "That enderchest is already being inspected.");
            return;
        }
        request.lease.set(lease);
        lease.phase.set(LeasePhase.OPENING);

        if (state.autosave != null) {
            state.autosave.cancel();
            state.autosave = null;
        }

        Inventory ownerInventory = state.inventory;
        if (target.getOpenInventory().getTopInventory() == ownerInventory) {
            target.closeInventory();
        }

        ChestSnapshot snapshot = this.snapshotContents(state, ownerInventory.getContents());
        if (snapshot == null) {
            state.activeLease.compareAndSet(lease, null);
            lease.phase.set(LeasePhase.RELEASED);
            request.lease.compareAndSet(lease, null);
            this.failInspectionRequest(request, "That enderchest could not be saved.");
            this.startOwnerAutosave(target, targetId, state);
            return;
        }
        state.inventory = null;
        this.persistSnapshot(targetId, snapshot).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logSaveFailure(error);
            }
        });

        Player viewer = request.viewer.get();
        if (viewer == null || request.cancelled.get()) {
            this.finishInspectionFromSnapshot(lease);
            return;
        }
        try {
            this.runOnPlayer(viewer, current -> this.openInspectionProxy(current, lease, snapshot),
                    () -> this.finishInspectionFromSnapshot(lease));
        } catch (RuntimeException ex) {
            this.finishInspectionFromSnapshot(lease);
        }
    }

    private void openInspectionProxy(Player viewer, InspectionLease lease, ChestSnapshot snapshot) {
        if (lease.finalizing.get() || lease.request.cancelled.get()
                || lease.state.activeLease.get() != lease
                || this.inspectionRequests.get(lease.viewerId) != lease.request
                || !viewer.isOnline()) {
            this.finishInspectionFromSnapshot(lease);
            return;
        }

        InspectionHolder holder = new InspectionHolder(lease.token);
        Inventory proxy = Bukkit.createInventory(holder, 54, Component.text("Enderchest"));
        holder.inventory = proxy;
        this.copySnapshotToInventory(snapshot, proxy);
        lease.proxyInventory = proxy;

        try {
            viewer.openInventory(proxy);
        } catch (RuntimeException ex) {
            this.finishInspectionFromSnapshot(lease);
            return;
        }
        if (viewer.getOpenInventory().getTopInventory() != proxy) {
            this.finishInspectionFromSnapshot(lease);
            return;
        }

        lease.phase.set(LeasePhase.ACTIVE);
        lease.autosave = PlatformScheduler.entityRepeating(this, viewer,
                () -> this.autosaveInspection(viewer, lease),
                () -> this.finishInspectionFromSnapshot(lease),
                AUTOSAVE_INITIAL_DELAY_TICKS, AUTOSAVE_PERIOD_TICKS);
    }

    private void autosaveInspection(Player viewer, InspectionLease lease) {
        if (lease.phase.get() != LeasePhase.ACTIVE || lease.finalizing.get()
                || lease.state.activeLease.get() != lease || lease.proxyInventory == null
                || viewer.getOpenInventory().getTopInventory() != lease.proxyInventory) {
            return;
        }
        ChestSnapshot snapshot = this.snapshotContents(lease.state, lease.proxyInventory.getContents());
        if (snapshot != null) {
            this.persistSnapshot(lease.targetId, snapshot).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.logSaveFailure(error);
                }
            });
        }
    }

    private void finishInspectionFromViewer(InspectionLease lease, Inventory proxy) {
        if (proxy != lease.proxyInventory || !lease.finalizing.compareAndSet(false, true)) {
            return;
        }
        lease.phase.set(LeasePhase.CLOSING);
        if (lease.autosave != null) {
            lease.autosave.cancel();
            lease.autosave = null;
        }

        ChestSnapshot snapshot = this.snapshotContents(lease.state, proxy.getContents());
        this.persistAndReleaseInspection(lease, snapshot);
    }

    private void finishInspectionFromSnapshot(InspectionLease lease) {
        if (!lease.finalizing.compareAndSet(false, true)) {
            return;
        }
        lease.phase.set(LeasePhase.CLOSING);
        if (lease.autosave != null) {
            lease.autosave.cancel();
            lease.autosave = null;
        }
        this.persistAndReleaseInspection(lease, lease.state.snapshot.get());
    }

    private void persistAndReleaseInspection(InspectionLease lease, ChestSnapshot snapshot) {
        if (snapshot == null) {
            this.completeInspectionLease(lease, false);
            return;
        }
        this.persistSnapshot(lease.targetId, snapshot).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logSaveFailure(error);
            }
            this.completeInspectionLease(lease, error == null);
        });
    }

    private void completeInspectionLease(InspectionLease lease, boolean saved) {
        Player target = lease.state.onlinePlayer.get();
        if (target != null) {
            try {
                this.runOnPlayer(target, owner -> this.releaseInspectionOnOwner(owner, lease, saved),
                        () -> this.releaseInspectionWithoutOwner(lease, target, saved));
                return;
            } catch (RuntimeException ex) {
                this.releaseInspectionWithoutOwner(lease, target, saved);
                return;
            }
        }
        this.releaseInspectionWithoutOwner(lease, null, saved);
    }

    private void releaseInspectionOnOwner(Player target, InspectionLease lease, boolean saved) {
        if (lease.state.activeLease.get() != lease) {
            this.finishInspectionRequest(lease);
            return;
        }
        lease.state.activeLease.compareAndSet(lease, null);
        lease.phase.set(LeasePhase.RELEASED);
        this.finishInspectionRequest(lease);

        if (this.chests.get(lease.targetId) != lease.state || !target.isOnline()) {
            return;
        }
        if (lease.state.inventory == null) {
            ChestSnapshot snapshot = lease.state.snapshot.get();
            if (snapshot != null) {
                lease.state.inventory = this.createOwnerInventory(snapshot);
                this.startOwnerAutosave(target, lease.targetId, lease.state);
            }
        }
        if (!saved) {
            this.persistLatestSnapshot(lease.targetId, lease.state).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.logSaveFailure(error);
                }
            });
        }
    }

    private void releaseInspectionWithoutOwner(InspectionLease lease, Player retiredTarget, boolean saved) {
        if (retiredTarget != null) {
            lease.state.onlinePlayer.compareAndSet(retiredTarget, null);
        }

        AtomicReference<Player> online = new AtomicReference<>();
        this.chests.compute(lease.targetId, (ignored, current) -> {
            if (current != lease.state) {
                return current;
            }
            Player currentPlayer = lease.state.onlinePlayer.get();
            if (currentPlayer != null) {
                online.set(currentPlayer);
                return current;
            }
            lease.state.activeLease.compareAndSet(lease, null);
            lease.phase.set(LeasePhase.RELEASED);
            return saved ? null : current;
        });

        Player currentTarget = online.get();
        if (currentTarget != null) {
            try {
                this.runOnPlayer(currentTarget, owner -> this.releaseInspectionOnOwner(owner, lease, saved),
                        () -> this.releaseInspectionWithoutOwner(lease, currentTarget, saved));
                return;
            } catch (RuntimeException ex) {
                this.releaseInspectionWithoutOwner(lease, currentTarget, saved);
                return;
            }
        }
        this.finishInspectionRequest(lease);
    }

    private void requestViewerClose(InspectionLease lease) {
        lease.request.cancelled.set(true);
        Player viewer = lease.request.viewer.get();
        if (viewer == null) {
            this.finishInspectionFromSnapshot(lease);
            return;
        }
        try {
            this.runOnPlayer(viewer, current -> {
                Inventory proxy = lease.proxyInventory;
                if (!lease.finalizing.get() && proxy != null
                        && current.getOpenInventory().getTopInventory() == proxy) {
                    current.closeInventory();
                } else {
                    this.finishInspectionFromSnapshot(lease);
                }
            }, () -> this.finishInspectionFromSnapshot(lease));
        } catch (RuntimeException ex) {
            this.finishInspectionFromSnapshot(lease);
        }
    }

    private void finishInspectionRequest(InspectionLease lease) {
        lease.phase.set(LeasePhase.RELEASED);
        lease.request.viewer.set(null);
        this.inspectionRequests.remove(lease.viewerId, lease.request);
    }

    private void failInspectionRequest(InspectionRequest request, String message) {
        request.cancelled.set(true);
        InspectionLease lease = request.lease.get();
        if (lease == null) {
            this.inspectionRequests.remove(request.viewerId, request);
        } else if (!lease.finalizing.get()) {
            this.finishInspectionFromSnapshot(lease);
        }
        if (message != null) {
            Player viewer = request.viewer.get();
            if (viewer != null) {
                sendActionBar(viewer, message, NamedTextColor.RED);
            }
        }
    }

    private void withExpandedEnderchest(Player player, Consumer<Inventory> ready, Runnable retired) {
        UUID uuid = player.getUniqueId();
        ChestState state = this.chests.computeIfAbsent(uuid, ignored -> new ChestState());
        state.onlinePlayer.set(player);
        if (state.activeLease.get() != null) {
            retired.run();
            return;
        }
        if (state.inventory != null) {
            ready.accept(state.inventory);
            return;
        }

        ChestSnapshot snapshot = state.snapshot.get();
        if (snapshot != null) {
            state.inventory = this.createOwnerInventory(snapshot);
            this.startOwnerAutosave(player, uuid, state);
            this.persistSnapshot(uuid, snapshot).whenComplete((ignored, error) -> {
                if (error != null) {
                    this.logSaveFailure(error);
                }
            });
            ready.accept(state.inventory);
            return;
        }

        synchronized (state.pendingLoads) {
            state.pendingLoads.add(new LoadAction(ready, retired));
            if (state.loading) {
                return;
            }
            state.loading = true;
        }

        this.readChestContentsOrdered(uuid).whenComplete((loaded, error) -> {
            LoadResult result = error == null ? loaded : new LoadResult(null, this.asException(error));
            this.runOnPlayer(player, owner -> this.finishLoad(owner, uuid, state, result),
                    () -> this.retireLoad(uuid, state));
        });
    }

    private void finishLoad(Player player, UUID uuid, ChestState state, LoadResult loaded) {
        if (!player.isOnline() || this.chests.get(uuid) != state || state.activeLease.get() != null) {
            this.retireLoad(uuid, state);
            return;
        }
        if (loaded.error() != null) {
            this.retireLoad(uuid, state);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, 54, Component.text("Enderchest"));
        ItemStack[] storedContents = this.deserializeContents(loaded);
        if (storedContents == null && loaded.serializedData() != null) {
            this.getLogger().warning("An expanded enderchest file contains unreadable item data; preserving the file.");
            this.retireLoad(uuid, state);
            return;
        }

        boolean shouldPersist = loaded.serializedData() == null;
        if (storedContents == null) {
            copyVanillaEnderchest(player, inventory);
        } else {
            this.copyContentsToInventory(storedContents, inventory);
        }

        state.inventory = inventory;
        if (shouldPersist) {
            ChestSnapshot snapshot = this.snapshotContents(state, inventory.getContents());
            if (snapshot != null) {
                this.persistSnapshot(uuid, snapshot).whenComplete((ignored, error) -> {
                    if (error != null) {
                        this.logSaveFailure(error);
                    }
                });
            }
        } else {
            state.snapshot.set(new ChestSnapshot(state.snapshotVersion.incrementAndGet(), loaded.serializedData()));
        }
        this.startOwnerAutosave(player, uuid, state);

        List<LoadAction> waiting;
        synchronized (state.pendingLoads) {
            state.loading = false;
            waiting = new ArrayList<>(state.pendingLoads);
            state.pendingLoads.clear();
        }
        for (LoadAction action : waiting) {
            try {
                action.ready().accept(inventory);
            } catch (RuntimeException ex) {
                this.getLogger().warning("An expanded enderchest request failed ("
                        + ex.getClass().getSimpleName() + ").");
            }
        }
    }

    private void retireLoad(UUID uuid, ChestState state) {
        this.chests.remove(uuid, state);
        List<LoadAction> waiting;
        synchronized (state.pendingLoads) {
            state.loading = false;
            waiting = new ArrayList<>(state.pendingLoads);
            state.pendingLoads.clear();
        }
        for (LoadAction action : waiting) {
            try {
                action.retired().run();
            } catch (RuntimeException ex) {
                this.getLogger().warning("An expanded enderchest request could not be completed.");
            }
        }
    }

    private void autosaveOwner(Player player, UUID uuid, ChestState state) {
        if (!player.isOnline() || this.chests.get(uuid) != state || state.inventory == null
                || state.activeLease.get() != null) {
            return;
        }
        this.captureOwnerInventory(uuid, state, state.inventory).whenComplete((ignored, error) -> {
            if (error != null) {
                this.logSaveFailure(error);
            }
        });
    }

    private void startOwnerAutosave(Player player, UUID uuid, ChestState state) {
        if (state.autosave != null) {
            state.autosave.cancel();
        }
        state.autosave = PlatformScheduler.entityRepeating(this, player,
                () -> this.autosaveOwner(player, uuid, state), () -> { },
                AUTOSAVE_INITIAL_DELAY_TICKS, AUTOSAVE_PERIOD_TICKS);
    }

    private CompletableFuture<Void> captureOwnerInventory(UUID uuid, ChestState state, Inventory inventory) {
        ChestSnapshot snapshot = this.snapshotContents(state, inventory.getContents());
        return snapshot == null ? CompletableFuture.failedFuture(new IOException("Could not serialize inventory."))
                : this.persistSnapshot(uuid, snapshot);
    }

    private ChestSnapshot snapshotContents(ChestState state, ItemStack[] contents) {
        ItemStack[] snapshotContents = copyContents(contents);
        byte[] serialized = this.serializeContents(snapshotContents);
        if (serialized == null) {
            return null;
        }
        ChestSnapshot snapshot = new ChestSnapshot(state.snapshotVersion.incrementAndGet(), serialized);
        state.snapshot.set(snapshot);
        return snapshot;
    }

    private byte[] serializeContents(ItemStack[] contents) {
        try (var buffer = new ByteArrayOutputStream();
             var output = new BukkitObjectOutputStream(buffer)) {
            output.writeObject(contents);
            output.flush();
            return buffer.toByteArray();
        } catch (IOException ex) {
            this.getLogger().warning("Could not serialize expanded enderchest data ("
                    + ex.getClass().getSimpleName() + ").");
            return null;
        }
    }

    private CompletableFuture<Void> persistLatestSnapshot(UUID uuid, ChestState state) {
        ChestSnapshot snapshot = state.snapshot.get();
        return snapshot == null ? CompletableFuture.completedFuture(null) : this.persistSnapshot(uuid, snapshot);
    }

    private CompletableFuture<Void> persistSnapshot(UUID uuid, ChestSnapshot snapshot) {
        if (snapshot == null || snapshot.serializedData == null) {
            return CompletableFuture.failedFuture(new IOException("No serialized enderchest snapshot is available."));
        }
        byte[] bytes = snapshot.serializedData.clone();
        return this.enqueueIo(uuid, () -> {
            this.writeChestContents(uuid, bytes);
            return null;
        });
    }

    private CompletableFuture<LoadResult> readChestContentsOrdered(UUID uuid) {
        return this.enqueueIo(uuid, () -> this.readChestContents(uuid));
    }

    private <T> CompletableFuture<T> enqueueIo(UUID uuid, IoCallable<T> operation) {
        if (!this.acceptingIo) {
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Enderchest file operations are shutting down."));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        IoLane lane = this.ioLanes.compute(uuid, (ignored, current) -> {
            IoLane selected = current == null ? new IoLane() : current;
            synchronized (selected) {
                selected.jobs.addLast(new IoJob<>(operation, result));
            }
            return selected;
        });
        this.startIoLane(uuid, lane);
        return result;
    }

    private void startIoLane(UUID uuid, IoLane lane) {
        IoJob<?> job;
        long attempt;
        long epoch = -1L;
        boolean shuttingDown;
        synchronized (lane) {
            if (lane.running) {
                return;
            }
            job = lane.jobs.peekFirst();
            if (job == null) {
                return;
            }
            shuttingDown = !this.acceptingIo;
            if (shuttingDown) {
                attempt = -1L;
            } else {
                lane.running = true;
                lane.activeJob = job;
                lane.activeTask = null;
                attempt = ++lane.attempt;
                epoch = this.ioEpoch;
            }
        }
        if (shuttingDown) {
            this.failIoLane(uuid, lane,
                    new RejectedExecutionException("Enderchest file operations stopped during shutdown."));
            return;
        }

        IoJob<?> selected = job;
        try {
            ScheduledTask submission = Bukkit.getAsyncScheduler().runNow(this, task -> {
                try {
                    selected.execute();
                } catch (Throwable ex) {
                    selected.fail(ex);
                } finally {
                    this.finishIoAttempt(uuid, lane, selected, attempt);
                }
            });

            boolean cancelForShutdown = false;
            synchronized (lane) {
                if (lane.running && lane.activeJob == selected && lane.attempt == attempt) {
                    lane.activeTask = submission;
                    cancelForShutdown = !this.acceptingIo || this.ioEpoch != epoch;
                }
            }
            if (cancelForShutdown) {
                this.cancelPendingIoAttempt(uuid, lane, selected, attempt, submission);
            }
        } catch (RuntimeException ex) {
            this.failIoAttempt(uuid, lane, selected, attempt, ex);
        }
    }

    private void finishIoAttempt(UUID uuid, IoLane lane, IoJob<?> job, long attempt) {
        synchronized (lane) {
            if (!lane.running || lane.activeJob != job || lane.attempt != attempt) {
                return;
            }
            if (lane.jobs.peekFirst() == job) {
                lane.jobs.removeFirst();
            }
            lane.activeTask = null;
            lane.activeJob = null;
            lane.running = false;
        }
        this.startIoLane(uuid, lane);
        this.removeIdleIoLane(uuid, lane);
    }

    private void failIoAttempt(UUID uuid, IoLane lane, IoJob<?> job, long attempt, Throwable failure) {
        List<IoJob<?>> failedJobs;
        synchronized (lane) {
            if (!lane.running || lane.activeJob != job || lane.attempt != attempt) {
                return;
            }
            failedJobs = new ArrayList<>(lane.jobs);
            lane.jobs.clear();
            lane.running = false;
            lane.activeTask = null;
            lane.activeJob = null;
            lane.attempt++;
        }
        this.finishFailedIoLane(uuid, lane, failedJobs, failure);
    }

    private void cancelPendingIoAttempt(UUID uuid, IoLane lane, IoJob<?> job, long attempt,
                                        ScheduledTask task) {
        ScheduledTask.CancelledState state = task.cancel();
        if (state == ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                || state == ScheduledTask.CancelledState.CANCELLED_ALREADY) {
            this.failIoAttempt(uuid, lane, job, attempt,
                    new RejectedExecutionException("An accepted enderchest file operation was cancelled before execution."));
        }
    }

    private void stopIoForShutdown() {
        RejectedExecutionException shutdown = new RejectedExecutionException(
                "Enderchest file operations were stopped during plugin shutdown.");
        for (var entry : this.ioLanes.entrySet()) {
            UUID uuid = entry.getKey();
            IoLane lane = entry.getValue();
            ScheduledTask activeTask;
            IoJob<?> activeJob;
            long attempt;
            boolean hasActiveAttempt;
            synchronized (lane) {
                hasActiveAttempt = lane.running && lane.activeJob != null;
                activeTask = lane.activeTask;
                activeJob = lane.activeJob;
                attempt = lane.attempt;
            }

            if (!hasActiveAttempt) {
                this.failIoLane(uuid, lane, shutdown);
                continue;
            }
            if (activeTask == null) {
                // Submission is in progress. startIoLane observes acceptingIo=false and cancels
                // a still-pending task as soon as the scheduler returns it. Keep only its head;
                // queued work is failed now so a rapid re-enable cannot revive stale lane work.
                this.failQueuedIoBehindActive(lane, activeJob, attempt, shutdown);
                continue;
            }

            try {
                ScheduledTask.CancelledState state = activeTask.cancel();
                if (state == ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                        || state == ScheduledTask.CancelledState.CANCELLED_ALREADY) {
                    this.failIoAttempt(uuid, lane, activeJob, attempt, shutdown);
                } else if (state == ScheduledTask.CancelledState.RUNNING
                        || state == ScheduledTask.CancelledState.ALREADY_EXECUTED) {
                    // The running operation owns completion; fail queued work behind it.
                    this.failQueuedIoBehindActive(lane, activeJob, attempt, shutdown);
                }
            } catch (RuntimeException ex) {
                this.getLogger().warning("Could not cancel an enderchest file task during shutdown ("
                        + ex.getClass().getSimpleName() + ").");
            }
        }
    }

    private void failQueuedIoBehindActive(IoLane lane, IoJob<?> activeJob, long attempt, Throwable failure) {
        List<IoJob<?>> failedJobs = new ArrayList<>();
        synchronized (lane) {
            if (!lane.running || lane.activeJob != activeJob || lane.attempt != attempt) {
                return;
            }
            Iterator<IoJob<?>> jobs = lane.jobs.iterator();
            if (jobs.hasNext()) {
                jobs.next();
            }
            while (jobs.hasNext()) {
                failedJobs.add(jobs.next());
                jobs.remove();
            }
        }
        if (!failedJobs.isEmpty()) {
            this.getLogger().warning("Failing " + failedJobs.size()
                    + " queued enderchest file operation(s) behind an in-flight shutdown operation.");
            for (IoJob<?> failedJob : failedJobs) {
                failedJob.fail(failure);
            }
        }
    }

    private void failIoLane(UUID uuid, IoLane lane, Throwable failure) {
        List<IoJob<?>> failedJobs;
        synchronized (lane) {
            failedJobs = new ArrayList<>(lane.jobs);
            lane.jobs.clear();
            lane.running = false;
            lane.activeTask = null;
            lane.activeJob = null;
            lane.attempt++;
        }
        this.finishFailedIoLane(uuid, lane, failedJobs, failure);
    }

    private void finishFailedIoLane(UUID uuid, IoLane lane, List<IoJob<?>> failedJobs, Throwable failure) {
        this.removeIdleIoLane(uuid, lane);
        this.getLogger().warning("Could not schedule or continue " + failedJobs.size()
                + " expanded enderchest file operation(s); failing queued work ("
                + failure.getClass().getSimpleName() + ").");
        for (IoJob<?> failedJob : failedJobs) {
            failedJob.fail(failure);
        }
    }

    private void removeIdleIoLane(UUID uuid, IoLane lane) {
        this.ioLanes.compute(uuid, (ignored, current) -> {
            if (current != lane) {
                return current;
            }
            synchronized (lane) {
                return lane.running || !lane.jobs.isEmpty() ? lane : null;
            }
        });
    }

    private int pendingIoCount() {
        int pending = 0;
        for (IoLane lane : this.ioLanes.values()) {
            synchronized (lane) {
                pending += lane.jobs.size();
            }
        }
        return pending;
    }

    private LoadResult readChestContents(UUID uuid) {
        Path file = this.enderchestFolder.resolve(uuid + ".dat");
        if (!Files.isRegularFile(file)) {
            return new LoadResult(null, null);
        }

        try (var fileInput = Files.newInputStream(file);
             var gzipInput = new GZIPInputStream(fileInput);
             var serialized = new ByteArrayOutputStream()) {
            gzipInput.transferTo(serialized);
            return new LoadResult(serialized.toByteArray(), null);
        } catch (IOException ex) {
            return new LoadResult(null, ex);
        }
    }

    private ItemStack[] deserializeContents(LoadResult loaded) {
        if (loaded.serializedData() == null) {
            if (loaded.error() != null) {
                this.getLogger().warning("Could not read an expanded enderchest file ("
                        + loaded.error().getClass().getSimpleName() + "); using the vanilla enderchest contents.");
            }
            return null;
        }

        try (var buffer = new ByteArrayInputStream(loaded.serializedData());
             var input = new BukkitObjectInputStream(buffer)) {
            Object value = input.readObject();
            if (value instanceof ItemStack[] items) {
                return items;
            }
            this.getLogger().warning("Expanded enderchest data has an unsupported format; using the vanilla enderchest contents.");
        } catch (IOException | ClassNotFoundException ex) {
            this.getLogger().warning("Could not deserialize expanded enderchest data ("
                    + ex.getClass().getSimpleName() + "); using the vanilla enderchest contents.");
        }
        return null;
    }

    private Inventory createOwnerInventory(ChestSnapshot snapshot) {
        Inventory inventory = Bukkit.createInventory(null, 54, Component.text("Enderchest"));
        this.copySnapshotToInventory(snapshot, inventory);
        return inventory;
    }

    private void copySnapshotToInventory(ChestSnapshot snapshot, Inventory inventory) {
        ItemStack[] contents = this.deserializeContents(new LoadResult(snapshot.serializedData, null));
        if (contents == null) {
            return;
        }
        this.copyContentsToInventory(contents, inventory);
    }

    private void copyContentsToInventory(ItemStack[] contents, Inventory inventory) {
        for (int slot = 0; slot < Math.min(contents.length, inventory.getSize()); slot++) {
            ItemStack item = contents[slot];
            if (item != null && item.getType() != Material.AIR) {
                inventory.setItem(slot, item.clone());
            }
        }
    }

    private void writeChestContents(UUID uuid, byte[] serialized) throws IOException {
        Path target = this.enderchestFolder.resolve(uuid + ".dat");
        Path temporary = this.enderchestFolder.resolve(uuid + ".dat.tmp-" + UUID.randomUUID());
        try {
            try (var fileOutput = Files.newOutputStream(temporary);
                 var gzipOutput = new GZIPOutputStream(fileOutput)) {
                gzipOutput.write(serialized);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void removeStateIfOffline(UUID uuid, ChestState state) {
        this.chests.compute(uuid, (ignored, current) -> {
            if (current != state || state.activeLease.get() != null || state.onlinePlayer.get() != null) {
                return current;
            }
            return null;
        });
    }

    private void logSaveFailure(Throwable error) {
        this.getLogger().warning("Could not save expanded enderchest data ("
                + this.asException(error).getClass().getSimpleName() + ").");
    }

    private Exception asException(Throwable error) {
        if (error instanceof Exception exception) {
            return exception;
        }
        return new IOException(error.getClass().getSimpleName());
    }

    private void runOnPlayer(Player player, Consumer<Player> action, Runnable retired) {
        // The shared helper hides a null scheduling result, which would strand lease/load cleanup.
        try {
            if (player.getScheduler().run(this, task -> action.accept(player), retired) == null) {
                retired.run();
            }
        } catch (RuntimeException ex) {
            retired.run();
        }
    }

    private void sendActionBar(Player player, String message, NamedTextColor color) {
        this.runOnPlayer(player, current -> current.sendActionBar(Component.text(message, color)), () -> { });
    }

    private static void copyVanillaEnderchest(Player player, Inventory inventory) {
        ItemStack[] vanilla = player.getEnderChest().getContents();
        for (int slot = 0; slot < Math.min(vanilla.length, inventory.getSize()); slot++) {
            ItemStack item = vanilla[slot];
            if (item != null && item.getType() != Material.AIR) {
                inventory.setItem(slot, item.clone());
            }
        }
    }

    private static ItemStack[] copyContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int slot = 0; slot < source.length; slot++) {
            ItemStack item = source[slot];
            copy[slot] = item == null ? null : item.clone();
        }
        return copy;
    }

    private final class InspectionHolder implements InventoryHolder {
        private final long token;
        private Inventory inventory;

        private InspectionHolder(long token) {
            this.token = token;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }
    }

    private enum LeasePhase {
        OPENING,
        ACTIVE,
        CLOSING,
        RELEASED
    }

    private final class InspectionRequest {
        private final UUID viewerId;
        private final AtomicReference<Player> viewer;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<InspectionLease> lease = new AtomicReference<>();

        private InspectionRequest(Player viewer) {
            this.viewerId = viewer.getUniqueId();
            this.viewer = new AtomicReference<>(viewer);
        }
    }

    private final class InspectionLease {
        private final long token;
        private final InspectionRequest request;
        private final UUID viewerId;
        private final UUID targetId;
        private final ChestState state;
        private final AtomicReference<LeasePhase> phase = new AtomicReference<>(LeasePhase.OPENING);
        private final AtomicBoolean finalizing = new AtomicBoolean();
        private volatile Inventory proxyInventory;
        private volatile TaskHandle autosave;

        private InspectionLease(long token, InspectionRequest request, UUID targetId, ChestState state) {
            this.token = token;
            this.request = request;
            this.viewerId = request.viewerId;
            this.targetId = targetId;
            this.state = state;
        }
    }

    private static final class ChestState {
        private final List<LoadAction> pendingLoads = new ArrayList<>();
        private final AtomicReference<ChestSnapshot> snapshot = new AtomicReference<>();
        private final AtomicLong snapshotVersion = new AtomicLong();
        private final AtomicReference<InspectionLease> activeLease = new AtomicReference<>();
        private final AtomicReference<Player> onlinePlayer = new AtomicReference<>();
        private Inventory inventory;
        private TaskHandle autosave;
        private volatile boolean loading;
    }

    private record ChestSnapshot(long version, byte[] serializedData) { }

    private record LoadAction(Consumer<Inventory> ready, Runnable retired) { }

    private record LoadResult(byte[] serializedData, Exception error) { }

    private static final class IoLane {
        private final Deque<IoJob<?>> jobs = new ArrayDeque<>();
        private boolean running;
        private long attempt;
        private IoJob<?> activeJob;
        private ScheduledTask activeTask;
    }

    private static final class IoJob<T> {
        private final IoCallable<T> operation;
        private final CompletableFuture<T> completion;

        private IoJob(IoCallable<T> operation, CompletableFuture<T> completion) {
            this.operation = operation;
            this.completion = completion;
        }

        private void execute() throws Exception {
            this.completion.complete(this.operation.call());
        }

        private void fail(Throwable error) {
            this.completion.completeExceptionally(error);
        }
    }

    @FunctionalInterface
    private interface IoCallable<T> {
        T call() throws Exception;
    }
}
