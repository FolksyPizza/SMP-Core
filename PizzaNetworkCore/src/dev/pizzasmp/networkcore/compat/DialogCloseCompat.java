package dev.pizzasmp.networkcore.compat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closes Paper dialogs across API versions with and without Player.closeDialog(). */
public final class DialogCloseCompat {
    private static final AtomicBoolean WARNING_SENT = new AtomicBoolean();

    private DialogCloseCompat() {
    }

    public static boolean close(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        try {
            Method closeDialog = player.getClass().getMethod("closeDialog");
            closeDialog.invoke(player);
            return true;
        } catch (NoSuchMethodException ignored) {
            // Older Paper exposes Dialogs without a Player close method.
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Fall through to the protocol packet when the Paper method is absent or unusable.
        }

        if (player.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try {
                ProtocolManager manager = ProtocolLibrary.getProtocolManager();
                PacketContainer clearDialog = manager.createPacket(PacketType.Play.Server.CLEAR_DIALOG);
                manager.sendServerPacket(player, clearDialog);
                return true;
            } catch (Exception | LinkageError ignored) {
                // The installed ProtocolLib may not know this packet on the current server version.
            }
        }

        if (WARNING_SENT.compareAndSet(false, true)) {
            player.getServer().getLogger().warning(
                "Unable to close a dialog on this Paper version; verify ProtocolLib supports CLEAR_DIALOG.");
        }
        return false;
    }
}
