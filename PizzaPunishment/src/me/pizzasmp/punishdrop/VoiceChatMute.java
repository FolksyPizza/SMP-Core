package me.pizzasmp.punishdrop;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Applies mutes to Simple Voice Chat: a muted player's microphone packets are dropped, in proximity and group
 * chat alike. Loaded only when the voicechat plugin is installed.
 *
 * Microphone packets arrive about fifty times a second per speaker on voice chat's network thread, so each
 * speaker's mute state is looked up at most once a second.
 */
final class VoiceChatMute implements VoicechatPlugin {
    private static final long CHECK_MILLIS = 1000L;
    private static final long NOTICE_MILLIS = 5000L;

    private final PunishDropPlugin plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private record State(long checkedAt, String notice, long noticedAt) {}

    private VoiceChatMute(PunishDropPlugin plugin) {
        this.plugin = plugin;
    }

    static boolean register(PunishDropPlugin plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) return false;
        service.registerPlugin(new VoiceChatMute(plugin));
        return true;
    }

    @Override
    public String getPluginId() {
        return "pizzapunishment";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || !(sender.getPlayer().getPlayer() instanceof Player player)) return;
        long now = System.currentTimeMillis();
        State state = this.states.get(player.getUniqueId());
        if (state == null || now - state.checkedAt() > CHECK_MILLIS) {
            String notice = this.plugin.muteNotice(player.getUniqueId(), player.getName());
            state = new State(now, notice, state == null ? 0L : state.noticedAt());
            this.states.put(player.getUniqueId(), state);
        }
        if (state.notice() == null) return;
        event.cancel();
        if (now - state.noticedAt() > NOTICE_MILLIS) {
            this.states.put(player.getUniqueId(), new State(state.checkedAt(), state.notice(), now));
            String notice = state.notice();
            player.getScheduler().run(this.plugin, task -> player.sendActionBar(Component.text(notice, NamedTextColor.RED)), null);
        }
    }
}
