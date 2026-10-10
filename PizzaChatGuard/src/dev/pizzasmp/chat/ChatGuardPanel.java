package dev.pizzasmp.chat;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * /chatguard: in-game view and control of the chat filter. Shows status, level and settings; switches the filter and
 * its level; views and edits each word list; manages exempt players. Changes are written to config.yml and applied
 * immediately.
 */
final class ChatGuardPanel {
    private static final NamedTextColor ACCENT = NamedTextColor.AQUA;
    private static final String[][] LISTS = {
        {"slur-words", "Slurs", "Blocked from Light up; offenders are muted"},
        {"light-words", "Worst words", "Blocked from Light up"},
        {"blocked-words", "Profanity", "Blocked from Normal up"},
        {"phrases", "Phrases", "Whole phrases, blocked from Normal up"},
        {"strong-words", "Mild words", "Blocked at Strong only"},
        {"safe-words", "Safe words", "Never blocked (prevents false positives)"}};

    private final PizzaChatGuard plugin;

    ChatGuardPanel(PizzaChatGuard plugin) {
        this.plugin = plugin;
    }

    void open(Player viewer) {
        var policy = plugin.currentPolicy();
        List<DialogBody> body = new ArrayList<>();
        body.add(line("Filter: " + (policy.enabled() ? "ON" : "OFF"), policy.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
        body.add(line("Level: " + label(policy.level()) + " - " + describe(policy.level()), NamedTextColor.WHITE));
        body.add(line("Lists: " + policy.slurWords().size() + " slurs, " + policy.lightWords().size() + " worst words, "
            + policy.blockedWords().size() + " profanity, " + policy.phrases().size() + " phrases, "
            + policy.strongWords().size() + " mild words, " + policy.safeWords().size() + " safe words", NamedTextColor.GRAY));
        body.add(line("Spam: " + policy.rateLimitMs() + " ms between messages, repeats within " + policy.duplicateWindowMs()
            + " ms. Caps: " + Math.round(policy.capsThreshold() * 100) + "% of " + policy.capsMinLength() + "+ letters. Links: "
            + (policy.advertPatterns().isEmpty() ? "allowed" : "blocked") + ".", NamedTextColor.GRAY));
        body.add(line(policy.listedOnly()
            ? "Applies to: listed players only (" + policy.watched().size() + ")"
            : "Applies to: everyone except " + policy.exempt().size() + " exempt player(s)", NamedTextColor.GRAY));
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(policy.enabled() ? "Turn filter OFF" : "Turn filter ON", policy.enabled() ? NamedTextColor.RED : NamedTextColor.GREEN,
            "Server-wide switch for all checks", p -> { save("enabled", !plugin.currentPolicy().enabled()); open(p); }));
        for (PizzaChatGuard.Level level : PizzaChatGuard.Level.values()) {
            boolean current = level == policy.level();
            buttons.add(button((current ? "> " : "") + label(level), current ? NamedTextColor.YELLOW : NamedTextColor.WHITE, describe(level),
                p -> { save("level", level.name().toLowerCase(Locale.ROOT)); open(p); }));
        }
        buttons.add(button("Word lists", ACCENT, "View and edit what each level blocks", this::lists));
        buttons.add(button(policy.listedOnly() ? "Applies to: listed" : "Applies to: everyone", ACCENT,
            "Switch between filtering everyone and filtering only the listed players",
            p -> { save("mode", plugin.currentPolicy().listedOnly() ? "everyone" : "listed"); open(p); }));
        buttons.add(button("Listed players", ACCENT, "Players the filter applies to in listed mode",
            p -> players(p, "only-players", "Listed players", "Nobody is listed.", "List")));
        buttons.add(button("Exempt players", ACCENT, "Players the filter ignores in everyone mode",
            p -> players(p, "exempt-players", "Exempt players", "Nobody is exempt.", "Exempt")));
        buttons.add(button("Reload config", NamedTextColor.GRAY, "Re-read config.yml", p -> { plugin.reloadFilter(); open(p); }));
        viewer.showDialog(dialog("ChatGuard", body, DialogType.multiAction(buttons).columns(3)
            .exitAction(button("Close", NamedTextColor.GRAY, null, null)).build(), List.of()));
    }

    private void lists(Player viewer) {
        List<ActionButton> buttons = new ArrayList<>();
        for (String[] list : LISTS) {
            int size = plugin.getConfig().getStringList(list[0]).size();
            buttons.add(button(list[1] + " (" + size + ")", ACCENT, list[2], p -> list(p, list)));
        }
        viewer.showDialog(dialog("Word lists", List.of(line("Each level includes the lists of the levels below it.", NamedTextColor.GRAY)),
            DialogType.multiAction(buttons).columns(2).exitAction(button("Back", NamedTextColor.GRAY, null, this::open)).build(), List.of()));
    }

    private void list(Player viewer, String[] list) {
        List<String> words = new ArrayList<>(plugin.getConfig().getStringList(list[0]));
        words.sort(String.CASE_INSENSITIVE_ORDER);
        List<DialogBody> body = new ArrayList<>();
        body.add(line(list[2] + ".", NamedTextColor.GRAY));
        StringBuilder row = new StringBuilder();
        for (String w : words) {
            if (row.length() + w.length() > 90) { body.add(line(row.toString(), NamedTextColor.WHITE)); row.setLength(0); }
            row.append(row.length() == 0 ? "" : ", ").append(w);
        }
        if (row.length() > 0) body.add(line(row.toString(), NamedTextColor.WHITE));
        if (words.isEmpty()) body.add(line("(empty)", NamedTextColor.DARK_GRAY));
        ActionButton add = input("Add", NamedTextColor.GREEN, "entry", (p, value) -> { edit(list[0], value, true); list(p, list); });
        ActionButton remove = input("Remove", NamedTextColor.RED, "entry", (p, value) -> { edit(list[0], value, false); list(p, list); });
        viewer.showDialog(dialog(list[1], body, DialogType.multiAction(List.of(add, remove)).columns(2)
            .exitAction(button("Back", NamedTextColor.GRAY, null, this::lists)).build(),
            List.of(DialogInput.text("entry", Component.text("Word or phrase")).maxLength(64).build())));
    }

    /** A list of players by UUID (exempt-players or only-players), shown by name, with add and remove. */
    private void players(Player viewer, String key, String title, String empty, String addLabel) {
        List<DialogBody> body = new ArrayList<>();
        List<String> ids = plugin.getConfig().getStringList(key);
        if (ids.isEmpty()) body.add(line(empty, NamedTextColor.GRAY));
        for (String id : ids) {
            String name;
            try { name = Bukkit.getOfflinePlayer(UUID.fromString(id)).getName(); } catch (IllegalArgumentException e) { name = null; }
            body.add(line(name == null ? id : name, NamedTextColor.WHITE));
        }
        ActionButton add = input(addLabel, NamedTextColor.GREEN, "player", (p, value) -> { playerEdit(p, key, value, true); players(p, key, title, empty, addLabel); });
        ActionButton remove = input("Remove", NamedTextColor.RED, "player", (p, value) -> { playerEdit(p, key, value, false); players(p, key, title, empty, addLabel); });
        viewer.showDialog(dialog(title, body, DialogType.multiAction(List.of(add, remove)).columns(2)
            .exitAction(button("Back", NamedTextColor.GRAY, null, this::open)).build(),
            List.of(DialogInput.text("player", Component.text("Player name or UUID")).maxLength(36).build())));
    }

    private void playerEdit(Player viewer, String key, String input, boolean add) {
        UUID id = null;
        try { id = UUID.fromString(input); } catch (IllegalArgumentException ignored) { }
        if (id == null) {
            Player online = Bukkit.getPlayerExact(input);
            OfflinePlayer known = online != null ? online : Bukkit.getOfflinePlayerIfCached(input);
            if (known == null) { viewer.sendMessage(Component.text(input + " has never joined.", NamedTextColor.RED)); return; }
            id = known.getUniqueId();
        }
        edit(key, id.toString(), add);
    }

    private void edit(String key, String value, boolean add) {
        String entry = value.trim();
        if (entry.isEmpty()) return;
        if (!key.equals("exempt-players") && !key.equals("only-players")) entry = entry.toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList(key));
        String finalEntry = entry;
        if (add) { if (list.stream().noneMatch(e -> e.equalsIgnoreCase(finalEntry))) list.add(entry); }
        else list.removeIf(e -> e.equalsIgnoreCase(finalEntry));
        save(key, list);
    }

    private void save(String key, Object value) {
        plugin.getConfig().set(key, value);
        plugin.saveConfig();
        plugin.reloadFilter();
    }

    private static String label(PizzaChatGuard.Level level) {
        return level.name().charAt(0) + level.name().substring(1).toLowerCase(Locale.ROOT);
    }

    private static String describe(PizzaChatGuard.Level level) {
        return switch (level) {
            case OFF -> "no word filtering (spam, caps and links still checked)";
            case LIGHT -> "only slurs and the worst single words";
            case NORMAL -> "slurs, profanity and hateful phrases";
            case STRONG -> "everything above plus mild swearing";
        };
    }

    private static DialogBody line(String text, NamedTextColor color) {
        return DialogBody.plainMessage(Component.text(text, color), 300);
    }

    private ActionButton button(String label, NamedTextColor color, String tooltip, Consumer<Player> click) {
        ActionButton.Builder b = ActionButton.builder(Component.text(label, color)).width(150);
        if (tooltip != null) b.tooltip(Component.text(tooltip, NamedTextColor.GRAY));
        if (click != null) b.action(DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) p.getScheduler().run(plugin, task -> click.accept(p), null);
        }, ClickCallback.Options.builder().build()));
        return b.build();
    }

    private ActionButton input(String label, NamedTextColor color, String key, java.util.function.BiConsumer<Player, String> click) {
        return ActionButton.builder(Component.text(label, color)).width(150).action(DialogAction.customClick((view, audience) -> {
            String value = view.getText(key);
            if (audience instanceof Player p && value != null && !value.isBlank()) p.getScheduler().run(plugin, task -> click.accept(p, value), null);
        }, ClickCallback.Options.builder().build())).build();
    }

    private static Dialog dialog(String title, List<DialogBody> body, DialogType type, List<DialogInput> inputs) {
        return Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(Component.text(title, ACCENT)).canCloseWithEscape(true).pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE).body(body).inputs(inputs).build())
            .type(type));
    }
}
