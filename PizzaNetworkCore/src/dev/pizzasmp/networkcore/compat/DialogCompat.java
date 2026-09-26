package dev.pizzasmp.networkcore.compat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Keeps Paper's optional dialog builder API out of the core plugin's linkage surface. */
public final class DialogCompat {
    private static final String PAPER_DIALOG = "io.papermc.paper.dialog.Dialog";
    private static final AtomicBoolean DISPLAY_WARNING_SENT = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();
    private static volatile Boolean supported;

    private DialogCompat() { }

    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) return cached;
        boolean available;
        try {
            Class<?> dialog = Class.forName(PAPER_DIALOG, false, DialogCompat.class.getClassLoader());
            ClassLoader loader = dialog.getClassLoader();
            Class.forName("io.papermc.paper.registry.data.dialog.DialogBase", false, loader);
            Class.forName("io.papermc.paper.registry.data.dialog.ActionButton", false, loader);
            Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput", false, loader);
            dialog.getMethod("create", Consumer.class);
            available = true;
        } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ex) {
            available = false;
        }
        supported = available;
        return available;
    }

    public static Dialog create(Component title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
        return new Dialog(title, body, inputs, type);
    }

    public static boolean show(Player player, Dialog dialog) {
        if (!isSupported()) {
            warnOnce(player, "This server version uses the legacy menu interface.");
            return false;
        }
        try {
            Object paperDialog = createPaperDialog(dialog);
            compatibleMethod(player.getClass(), "showDialog", paperDialog).invoke(player, paperDialog);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            supported = Boolean.FALSE;
            warnOnce(player, "This menu could not be opened.");
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                player.getServer().getLogger().log(Level.WARNING,
                    "Paper dialog construction failed; legacy menu fallbacks will be used where available.", ex);
            }
            return false;
        }
    }

    private static void warnOnce(Player player, String message) {
        if (DISPLAY_WARNING_SENT.compareAndSet(false, true)) player.sendActionBar(Component.text(message));
    }

    private static Object createPaperDialog(Dialog dialog) throws ReflectiveOperationException {
        ClassLoader loader = DialogCompat.class.getClassLoader();
        Object paperBase = buildPaperBase(loader, dialog);
        Object paperType = buildPaperType(loader, dialog.type);
        Consumer<Object> configure = factory -> {
            try {
                Object entry = invoke(factory, "empty");
                invoke(entry, "base", paperBase);
                invoke(entry, "type", paperType);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to configure Paper dialog", ex);
            }
        };
        Class<?> dialogClass = Class.forName(PAPER_DIALOG, true, loader);
        return invokeStatic(dialogClass, "create", configure);
    }

    private static Object buildPaperBase(ClassLoader loader, Dialog dialog) throws ReflectiveOperationException {
        Class<?> baseClass = Class.forName("io.papermc.paper.registry.data.dialog.DialogBase", true, loader);
        Object builder = invokeStatic(baseClass, "builder", dialog.title);
        invoke(builder, "canCloseWithEscape", true);
        invoke(builder, "pause", false);
        Class<?> afterAction = Class.forName(
            "io.papermc.paper.registry.data.dialog.DialogBase$DialogAfterAction", true, loader);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object none = Enum.valueOf((Class<? extends Enum>) afterAction.asSubclass(Enum.class), "NONE");
        invoke(builder, "afterAction", none);

        List<Object> body = new ArrayList<>(dialog.body.size());
        for (DialogBody part : dialog.body) body.add(buildPaperBody(loader, part));
        invoke(builder, "body", body);
        List<Object> inputs = new ArrayList<>(dialog.inputs.size());
        for (DialogInput input : dialog.inputs) inputs.add(buildPaperInput(loader, input));
        invoke(builder, "inputs", inputs);
        return invoke(builder, "build");
    }

    private static Object buildPaperBody(ClassLoader loader, DialogBody body) throws ReflectiveOperationException {
        Class<?> type = Class.forName("io.papermc.paper.registry.data.dialog.body.DialogBody", true, loader);
        if (body.item == null) return invokeStatic(type, "plainMessage", body.message);
        Object builder = invokeStatic(type, "item", body.item);
        return invoke(builder, "build");
    }

    private static Object buildPaperInput(ClassLoader loader, DialogInput input) throws ReflectiveOperationException {
        Class<?> type = Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput", true, loader);
        if (input.kind == DialogInput.Kind.TEXT) {
            Object builder = invokeStatic(type, "text", input.key, input.label);
            if (input.initialText != null) invoke(builder, "initial", input.initialText);
            invoke(builder, "width", input.width);
            invoke(builder, "maxLength", input.maxLength);
            return invoke(builder, "build");
        }
        if (input.kind == DialogInput.Kind.NUMBER_RANGE) {
            Object builder = invokeStatic(type, "numberRange", input.key, input.label, input.minimum, input.maximum);
            if (input.initialNumber != null) invoke(builder, "initial", input.initialNumber);
            if (input.step != null) invoke(builder, "step", input.step);
            invoke(builder, "width", input.width);
            return invoke(builder, "build");
        }
        Class<?> optionType = Class.forName(
            "io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput$OptionEntry", true, loader);
        List<Object> options = new ArrayList<>(input.options.size());
        for (DialogInput.OptionEntry option : input.options) {
            options.add(invokeStatic(optionType, "create", option.id, option.display, option.initial));
        }
        Object builder = invokeStatic(type, "singleOption", input.key, input.label, options);
        invoke(builder, "width", input.width);
        return invoke(builder, "build");
    }

    private static Object buildPaperType(ClassLoader loader, DialogType type) throws ReflectiveOperationException {
        Class<?> paperType = Class.forName("io.papermc.paper.registry.data.dialog.type.DialogType", true, loader);
        if (type.confirmation) {
            return invokeStatic(paperType, "confirmation",
                buildPaperButton(loader, type.buttons.get(0)), buildPaperButton(loader, type.buttons.get(1)));
        }
        List<Object> buttons = new ArrayList<>(type.buttons.size());
        for (ActionButton button : type.buttons) buttons.add(buildPaperButton(loader, button));
        Object builder = invokeStatic(paperType, "multiAction", buttons);
        invoke(builder, "columns", type.columns);
        if (type.exitAction != null) invoke(builder, "exitAction", buildPaperButton(loader, type.exitAction));
        return invoke(builder, "build");
    }

    private static Object buildPaperButton(ClassLoader loader, ActionButton button) throws ReflectiveOperationException {
        Class<?> type = Class.forName("io.papermc.paper.registry.data.dialog.ActionButton", true, loader);
        Object builder = invokeStatic(type, "builder", button.label);
        invoke(builder, "width", button.width);
        if (button.tooltip != null) invoke(builder, "tooltip", button.tooltip);
        if (button.action != null) invoke(builder, "action", buildPaperAction(loader, button.action));
        return invoke(builder, "build");
    }

    private static Object buildPaperAction(ClassLoader loader, DialogAction action) throws ReflectiveOperationException {
        Class<?> callbackType = Class.forName(
            "io.papermc.paper.registry.data.dialog.action.DialogActionCallback", true, loader);
        InvocationHandler callbackHandler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            if ("accept".equals(method.getName()) && args != null && args.length == 2) {
                action.handler.accept(new DialogView(args[0]), (Audience) args[1]);
            }
            return null;
        };
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, callbackHandler);
        Class<?> optionsType = Class.forName("net.kyori.adventure.text.event.ClickCallback$Options", true, loader);
        Object options = invoke(invokeStatic(optionsType, "builder"), "build");
        Class<?> actionType = Class.forName(
            "io.papermc.paper.registry.data.dialog.action.DialogAction", true, loader);
        return invokeStatic(actionType, "customClick", callback, options);
    }

    private static Object invokeStatic(Class<?> type, String name, Object... arguments) throws ReflectiveOperationException {
        Method method = compatibleMethod(type, name, arguments);
        if (!Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException(type.getName() + "." + name);
        return method.invoke(null, arguments);
    }

    private static Object invoke(Object target, String name, Object... arguments) throws ReflectiveOperationException {
        return compatibleMethod(target.getClass(), name, arguments).invoke(target, arguments);
    }

    private static Method compatibleMethod(Class<?> type, String name, Object... arguments) throws NoSuchMethodException {
        Method method = findCompatibleMethod(type, name, arguments);
        if (method != null) return method;
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] arguments) {
        if (type == null) return null;
        for (Class<?> contract : type.getInterfaces()) {
            if (Modifier.isPublic(contract.getModifiers())) {
                for (Method method : contract.getMethods()) {
                    if (matches(method, name, arguments)) return method;
                }
            }
            Method inherited = findCompatibleMethod(contract, name, arguments);
            if (inherited != null) return inherited;
        }
        for (Method method : type.getMethods()) {
            if (matches(method, name, arguments)) return method;
        }
        return findCompatibleMethod(type.getSuperclass(), name, arguments);
    }

    private static boolean matches(Method method, String name, Object[] arguments) {
        if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) return false;
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (!accepts(parameters[i], arguments[i])) return false;
        }
        return true;
    }

    private static boolean accepts(Class<?> parameter, Object argument) {
        if (argument == null) return !parameter.isPrimitive();
        if (!parameter.isPrimitive()) return parameter.isInstance(argument);
        return parameter == boolean.class && argument instanceof Boolean
            || parameter == int.class && argument instanceof Integer
            || parameter == long.class && argument instanceof Long
            || parameter == float.class && argument instanceof Float
            || parameter == double.class && argument instanceof Double;
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "DialogActionCallback";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    public static final class Dialog {
        private final Component title;
        private final List<DialogBody> body;
        private final List<DialogInput> inputs;
        private final DialogType type;

        private Dialog(Component title, List<DialogBody> body, List<DialogInput> inputs, DialogType type) {
            this.title = title;
            this.body = List.copyOf(body);
            this.inputs = List.copyOf(inputs);
            this.type = type;
        }
    }

    public static final class DialogBody {
        private final Component message;
        private final ItemStack item;

        private DialogBody(Component message, ItemStack item) {
            this.message = message;
            this.item = item;
        }

        public static DialogBody plainMessage(Component message) { return new DialogBody(message, null); }
        public static ItemBuilder item(ItemStack item) { return new ItemBuilder(item); }

        public static final class ItemBuilder {
            private final ItemStack item;
            private ItemBuilder(ItemStack item) { this.item = item; }
            public DialogBody build() { return new DialogBody(null, item); }
        }
    }

    public static final class DialogInput {
        private enum Kind { TEXT, NUMBER_RANGE, SINGLE_OPTION }
        private final String key;
        private final Component label;
        private final Kind kind;
        private final String initialText;
        private final Float minimum;
        private final Float maximum;
        private final Float initialNumber;
        private final Float step;
        private final List<OptionEntry> options;
        private final int width;
        private final int maxLength;

        private DialogInput(Builder builder) {
            this.key = builder.key;
            this.label = builder.label;
            this.kind = builder.kind;
            this.initialText = builder.initialText;
            this.minimum = builder.minimum;
            this.maximum = builder.maximum;
            this.initialNumber = builder.initialNumber;
            this.step = builder.step;
            this.options = List.copyOf(builder.options);
            this.width = builder.width;
            this.maxLength = builder.maxLength;
        }

        public static Builder text(String key, Component label) { return new Builder(key, label); }
        public static Builder numberRange(String key, Component label, float minimum, float maximum) {
            return new Builder(key, label).numberRange(minimum, maximum);
        }
        public static Builder singleOption(String key, Component label, List<OptionEntry> options) {
            return new Builder(key, label).singleOption(options);
        }

        public static final class OptionEntry {
            private final String id;
            private final Component display;
            private final boolean initial;

            private OptionEntry(String id, Component display, boolean initial) {
                this.id = id;
                this.display = display;
                this.initial = initial;
            }

            public static OptionEntry create(String id, Component display, boolean initial) {
                return new OptionEntry(id, display, initial);
            }
        }

        public static final class Builder {
            private final String key;
            private final Component label;
            private Kind kind = Kind.TEXT;
            private String initialText;
            private Float minimum;
            private Float maximum;
            private Float initialNumber;
            private Float step;
            private List<OptionEntry> options = List.of();
            private int width = 200;
            private int maxLength = 32;

            private Builder(String key, Component label) {
                this.key = key;
                this.label = label;
            }

            private Builder numberRange(float min, float max) {
                this.kind = Kind.NUMBER_RANGE;
                this.minimum = min;
                this.maximum = max;
                return this;
            }
            private Builder singleOption(List<OptionEntry> value) {
                this.kind = Kind.SINGLE_OPTION;
                this.options = List.copyOf(value);
                return this;
            }
            public Builder initial(String value) { this.initialText = value; return this; }
            public Builder initial(float value) { this.initialNumber = value; return this; }
            public Builder step(float value) { this.step = value; return this; }
            public Builder width(int value) { this.width = value; return this; }
            public Builder maxLength(int value) { this.maxLength = value; return this; }
            public DialogInput build() { return new DialogInput(this); }
        }
    }

    public static final class ActionButton {
        private final Component label;
        private final Component tooltip;
        private final int width;
        private final DialogAction action;

        private ActionButton(Builder builder) {
            this.label = builder.label;
            this.tooltip = builder.tooltip;
            this.width = builder.width;
            this.action = builder.action;
        }

        public static Builder builder(Component label) { return new Builder(label); }

        public static final class Builder {
            private final Component label;
            private Component tooltip;
            private int width = 150;
            private DialogAction action;

            private Builder(Component label) { this.label = label; }
            public Builder tooltip(Component value) { this.tooltip = value; return this; }
            public Builder width(int value) { this.width = value; return this; }
            public Builder action(DialogAction value) { this.action = value; return this; }
            public ActionButton build() { return new ActionButton(this); }
        }
    }

    public static final class DialogAction {
        private final ClickHandler handler;
        private DialogAction(ClickHandler handler) { this.handler = handler; }
        public static DialogAction customClick(ClickHandler handler, ClickCallback.Options ignored) {
            return new DialogAction(handler);
        }
    }

    @FunctionalInterface
    public interface ClickHandler {
        void accept(DialogView view, Audience audience);
    }

    public static final class DialogView {
        private final Object delegate;
        private DialogView(Object delegate) { this.delegate = delegate; }
        public String getText(String key) {
            try { return (String) invoke(delegate, "getText", key); }
            catch (ReflectiveOperationException ex) { return null; }
        }
        public Float getFloat(String key) {
            try { return (Float) invoke(delegate, "getFloat", key); }
            catch (ReflectiveOperationException ex) { return null; }
        }
    }

    public static final class DialogType {
        private final boolean confirmation;
        private final List<ActionButton> buttons;
        private final int columns;
        private final ActionButton exitAction;

        private DialogType(boolean confirmation, List<ActionButton> buttons, int columns, ActionButton exitAction) {
            this.confirmation = confirmation;
            this.buttons = List.copyOf(buttons);
            this.columns = columns;
            this.exitAction = exitAction;
        }

        public static DialogType confirmation(ActionButton yes, ActionButton no) {
            return new DialogType(true, List.of(yes, no), 1, null);
        }

        public static MultiActionBuilder multiAction(List<ActionButton> buttons) {
            return new MultiActionBuilder(buttons);
        }

        public static final class MultiActionBuilder {
            private final List<ActionButton> buttons;
            private int columns = 1;
            private ActionButton exitAction;
            private MultiActionBuilder(List<ActionButton> buttons) { this.buttons = List.copyOf(buttons); }
            public MultiActionBuilder columns(int value) { this.columns = value; return this; }
            public MultiActionBuilder exitAction(ActionButton value) { this.exitAction = value; return this; }
            public DialogType build() { return new DialogType(false, buttons, columns, exitAction); }
        }
    }
}
