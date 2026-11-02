package com.corebound.thecore.command;

import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import com.corebound.thecore.TheCorePlugin;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class CoreCommand implements CommandExecutor, TabCompleter {
    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final IslandRepository repository;
    private final Messages messages;

    public CoreCommand(
            TheCorePlugin plugin, IslandService islandService, IslandRepository repository, Messages messages) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.repository = repository;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return true;
        }
        if (args.length == 0) {
            messages.send(player, "usage");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length != 2) {
                    messages.send(player, "usage");
                    return true;
                }
                islandService.create(player, args[1]);
            }
            case "home" -> {
                if (args.length != 1) {
                    messages.send(player, "usage");
                    return true;
                }
                home(player);
            }
            case "visit" -> visit(player, args);
            case "trust" -> trust(player, args);
            case "open" -> setOpen(player, args, true);
            case "close" -> setOpen(player, args, false);
            case "bypass" -> bypass(player, args);
            default -> messages.send(player, "usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "home", "visit", "trust", "open", "close", "bypass").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("visit") || args[0].equalsIgnoreCase("trust"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private void home(Player player) {
        Optional<IslandData> island = repository.byOwner(player.getUniqueId());
        if (island.isEmpty()) {
            messages.send(player, "island-not-created");
            return;
        }
        if (islandService.teleport(player, island.get())) {
            messages.send(player, "teleported-home");
        } else {
            messages.send(player, "teleport-failed");
        }
    }

    private void visit(Player player, String[] args) {
        if (args.length != 2) {
            messages.send(player, "usage");
            return;
        }
        Optional<IslandData> island = repository.byName(args[1]);
        if (island.isEmpty()) {
            island = repository.all().stream()
                    .filter(data -> {
                        OfflinePlayer owner = Bukkit.getOfflinePlayer(data.owner());
                        return args[1].equalsIgnoreCase(owner.getName());
                    })
                    .findFirst();
        }
        if (island.isEmpty()) {
            messages.send(player, "island-not-found");
            return;
        }
        IslandData destination = island.get();
        if (!destination.open()
                && !destination.owner().equals(player.getUniqueId())
                && !islandService.hasBypass(player.getUniqueId())) {
            messages.send(player, "island-closed");
            return;
        }
        if (!islandService.teleport(player, destination)) {
            messages.send(player, "teleport-failed");
            return;
        }

        String ownerName = Optional.ofNullable(Bukkit.getOfflinePlayer(destination.owner()).getName())
                .orElse(destination.name());
        messages.send(player, "teleported-visit", Map.of("name", ownerName));
    }

    private void setOpen(Player player, String[] args, boolean open) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        Optional<IslandData> ownedIsland = repository.byOwner(player.getUniqueId());
        if (ownedIsland.isEmpty()) {
            messages.send(player, "island-not-created");
            return;
        }

        IslandData island = ownedIsland.get();
        if (island.open() == open) {
            messages.send(player, open ? "island-already-open" : "island-already-closed");
            return;
        }
        try {
            repository.replace(island.withOpen(open));
        } catch (java.io.IOException exception) {
            plugin.getLogger().severe("Could not update access for island '" + island.name() + "': "
                    + exception.getMessage());
            messages.send(player, "data-save-failed");
            return;
        }
        if (!open) {
            islandService.ejectVisitors(island);
        }
        messages.send(player, open ? "island-opened" : "island-closed-by-owner");
    }

    private void bypass(Player player, String[] args) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        if (!player.hasPermission("thecore.bypass")) {
            messages.send(player, "bypass-no-permission");
            return;
        }
        boolean enabled = islandService.toggleBypass(player.getUniqueId());
        messages.send(player, enabled ? "bypass-enabled" : "bypass-disabled");
    }

    private void trust(Player player, String[] args) {
        if (args.length != 2) {
            messages.send(player, "trust-usage");
            return;
        }
        Optional<IslandData> ownedIsland = repository.byOwner(player.getUniqueId());
        if (ownedIsland.isEmpty()) {
            messages.send(player, "island-not-created");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(player, "trust-player-not-found");
            return;
        }
        UUID targetId = target.getUniqueId();
        if (targetId.equals(player.getUniqueId())) {
            messages.send(player, "trust-self");
            return;
        }

        IslandData island = ownedIsland.get();
        boolean add = !island.trustedMembers().contains(targetId);
        try {
            repository.replace(island.withTrustedMember(targetId, add));
        } catch (java.io.IOException exception) {
            plugin.getLogger().severe("Could not update trusted members for island '" + island.name() + "': "
                    + exception.getMessage());
            messages.send(player, "data-save-failed");
            return;
        }
        messages.send(
                player,
                add ? "trust-added" : "trust-removed",
                Map.of("name", target.getName()));
    }
}
