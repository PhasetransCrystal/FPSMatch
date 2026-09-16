package com.ptcrys.fpsmatch.common.command;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Descriptions are registered by modules; command structure and permissions come from Brigadier. */
public final class FPSMHelpManager {
    private record Description(MutableComponent text, @Nullable MutableComponent hover) {}
    private static final FPSMHelpManager INSTANCE = new FPSMHelpManager();
    private final Map<String, Description> descriptions = new HashMap<>();
    private final Map<CommandNode<CommandSourceStack>, Integer> ids = new IdentityHashMap<>();
    private final Map<Integer, List<CommandNode<CommandSourceStack>>> paths = new HashMap<>();
    private final Map<String, Set<Integer>> toggledByViewer = new HashMap<>();
    private CommandNode<CommandSourceStack> root;
    private int nextId;

    private FPSMHelpManager() { reset(); }
    public static FPSMHelpManager getInstance() { return INSTANCE; }

    public void reset() {
        descriptions.clear();
        ids.clear();
        paths.clear();
        toggledByViewer.clear();
        root = null;
        // Do not reuse IDs: old chat links must not toggle unrelated nodes after a reload.
        registerCommandHelp("fpsm", "commands.fpsm.help.header");
        registerCommandHelp("fpsm help", "commands.fpsm.help.basic.help");
        registerCommandHelp("fpsm help toggle", "commands.fpsm.help.toggle");
    }

    public void bind(CommandNode<CommandSourceStack> root) {
        this.root = root;
        ids.clear();
        paths.clear();
        toggledByViewer.clear();
        index(root, List.of());
    }

    private void index(CommandNode<CommandSourceStack> node, List<CommandNode<CommandSourceStack>> parents) {
        List<CommandNode<CommandSourceStack>> path = new ArrayList<>(parents);
        path.add(node);
        int id = ++nextId;
        ids.put(node, id);
        paths.put(id, List.copyOf(path));
        for (var child : node.getChildren()) index(child, path);
    }

    private static String normalize(String path) {
        String result = path.trim().replaceAll("\\s+", " ");
        if (!result.equals("fpsm") && !result.startsWith("fpsm ")) {
            throw new IllegalArgumentException("Help paths must start with fpsm: " + path);
        }
        return result;
    }

    public void registerCommandHelp(String path, MutableComponent description) {
        registerCommandHelp(path, description, null);
    }

    public void registerCommandHelp(String path, MutableComponent description, @Nullable MutableComponent hover) {
        String key = normalize(path);
        // Empty category registrations must not erase an existing description.
        if (!description.equals(Component.empty()) || !descriptions.containsKey(key)) {
            descriptions.put(key, new Description(description.copy(), hover == null ? null : hover.copy()));
        }
    }

    public void registerCommandHelp(String path, String key) { registerCommandHelp(path, Component.translatable(key)); }
    public void registerCommandHelp(String path) { registerCommandHelp(path, Component.empty()); }

    /** Compatibility API: argument order and optional branches now come from the registered command tree. */
    public void registerCommandParameters(String path, String... parameters) { normalize(path); }

    public boolean addChildCommand(String path, String child, MutableComponent description) {
        registerCommandHelp(path + " " + child, description);
        return true;
    }

    private static String viewer(@Nullable CommandSourceStack source) {
        if (source == null) return "preview";
        return source.getEntity() == null ? "source:" + source.getTextName() : source.getEntity().getUUID().toString();
    }

    private List<CommandNode<CommandSourceStack>> visibleChildren(CommandNode<CommandSourceStack> node,
                                                                  @Nullable CommandSourceStack source) {
        return node.getChildren().stream().filter(child -> source == null || child.canUse(source))
                .sorted(Comparator.comparing(CommandNode::getName)).toList();
    }

    public boolean toggleNodeExpanded(int id) { return toggleNodeExpanded(id, null); }

    public boolean toggleNodeExpanded(int id, @Nullable CommandSourceStack source) {
        var path = paths.get(id);
        if (path == null || source != null && path.stream().anyMatch(node -> !node.canUse(source))) return false;
        if (visibleChildren(path.get(path.size() - 1), source).size() <= 1) return false;
        Set<Integer> toggled = toggledByViewer.computeIfAbsent(viewer(source), ignored -> new HashSet<>());
        if (!toggled.add(id)) toggled.remove(id);
        return true;
    }

    private void render(CommandNode<CommandSourceStack> node, String literalPath, String fullPath, int depth,
                        @Nullable CommandSourceStack source, List<MutableComponent> lines) {
        boolean argument = node instanceof ArgumentCommandNode<?, ?>;
        String label = argument ? "<" + node.getName() + ">" : node.getName();
        String commandPath = fullPath.isEmpty() ? label : fullPath + " " + label;
        String helpPath = argument ? literalPath : literalPath.isEmpty() ? node.getName() : literalPath + " " + node.getName();
        Description description = descriptions.get(argument ? helpPath + " " + node.getName() : helpPath);
        var children = visibleChildren(node, source);
        int id = ids.get(node);
        boolean collapsible = depth > 0 && children.size() > 1;
        boolean expanded = !collapsible || toggledByViewer.getOrDefault(viewer(source), Set.of()).contains(id);
        MutableComponent line = Component.literal(depth == 0 ? "/" + label : "  ".repeat(depth) + "└─ " + label)
                .withStyle(argument ? ChatFormatting.WHITE : ChatFormatting.AQUA);
        if (description != null && !description.text().getString().isEmpty()) {
            line.append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(description.text().copy().withStyle(ChatFormatting.GRAY));
        }
        if (collapsible) {
            line.append(Component.literal(" ["))
                    .append(Component.translatable(expanded ? "commands.fpsm.help.node.toggle.collapse" : "commands.fpsm.help.node.toggle.expand"))
                    .append(Component.literal("]"));
        }
        Style style = Style.EMPTY.withClickEvent(new ClickEvent(
                collapsible ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND,
                collapsible ? "/fpsm help toggle " + id : "/" + commandPath));
        style = style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                description != null && description.hover() != null ? description.hover().copy() : Component.literal("/" + commandPath)));
        lines.add(line.withStyle(style).append("\n"));
        if (expanded) {
            for (var child : children) render(child, helpPath, commandPath, depth + 1, source, lines);
        } else {
            lines.add(Component.literal("  ".repeat(depth + 1))
                    .append(Component.translatable("commands.fpsm.help.node.expand", children.size()))
                    .withStyle(ChatFormatting.GRAY).append("\n"));
        }
    }

    public List<MutableComponent> getCommandTree() { return getCommandTree(null); }
    public List<MutableComponent> getCommandTree(@Nullable CommandSourceStack source) {
        List<MutableComponent> lines = new ArrayList<>();
        if (root != null && (source == null || root.canUse(source))) render(root, "", "", 0, source, lines);
        return lines;
    }

    public MutableComponent buildHelpMessage(Component header, List<MutableComponent> entries) {
        MutableComponent result = Component.empty().append(header.copy()).append("\n\n");
        if (entries.isEmpty()) result.append(Component.translatable("commands.fpsm.help.no_entries"));
        else entries.forEach(result::append);
        return result;
    }

    public MutableComponent buildCommandTreeHelp() { return buildCommandTreeHelp(null); }
    public MutableComponent buildCommandTreeHelp(@Nullable CommandSourceStack source) {
        return buildHelpMessage(Component.translatable("commands.fpsm.help.header"), getCommandTree(source));
    }

    public static String withTeamCapability(String command) { return "fpsm map modify team teams capability " + command; }
    public static String withMapCapability(String command) { return "fpsm map modify capability " + command; }
}
