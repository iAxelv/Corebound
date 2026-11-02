package com.corebound.thecore.command;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.gui.IslandMenuManager;
import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import java.io.IOException;
import java.util.ArrayList;
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
    private static final List<String> SUBCOMMANDS = List.of(
            "create", "home", "visit", "invite", "accept", "coop", "open", "close",
            "settings", "members", "delete", "leave", "kick", "ban", "unban", "bypass");
    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final IslandRepository repository;
    private final Messages messages;
    private final IslandMenuManager menus;

    public CoreCommand(
            TheCorePlugin plugin,
            IslandService islandService,
            IslandRepository repository,
            Messages messages,
            IslandMenuManager menus) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.repository = repository;
        this.messages = messages;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                if (args.length == 2) {
                    islandService.create(player, args[1]);
                } else {
                    messages.send(player, "usage");
                }
            }
            case "home" -> home(player, args);
            case "visit" -> visit(player, args);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player, args);
            case "coop" -> coop(player, args);
            case "open" -> setOpen(player, args, true);
            case "close" -> setOpen(player, args, false);
            case "bypass" -> bypass(player, args);
            case "settings" -> openSettings(player, args);
            case "members" -> openMembers(player, args);
            case "delete" -> delete(player, args);
            case "leave" -> leave(player, args);
            case "kick" -> memberAction(player, args, MemberAction.KICK);
            case "ban" -> memberAction(player, args, MemberAction.BAN);
            case "unban" -> memberAction(player, args, MemberAction.UNBAN);
            default -> messages.send(player, "usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && List.of("invite", "coop", "kick", "ban", "unban").contains(
                args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("accept") && sender instanceof Player player) {
            return islandService.pendingInviterNames(player.getUniqueId()).stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private void home(Player player, String[] args) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        Optional<IslandData> island = repository.forPlayer(player.getUniqueId());
        if (island.isEmpty()) {
            messages.send(player, "island-not-created");
        } else if (islandService.teleport(player, island.get())) {
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
                    .filter(data -> args[1].equalsIgnoreCase(
                            Optional.ofNullable(Bukkit.getOfflinePlayer(data.owner()).getName()).orElse("")))
                    .findFirst();
        }
        if (island.isEmpty()) {
            messages.send(player, "island-not-found");
            return;
        }
        IslandData destination = island.get();
        UUID playerId = player.getUniqueId();
        if (islandService.isDeleting(destination.owner())) {
            messages.send(player, "island-being-deleted");
            return;
        }
        if (destination.bannedPlayers().contains(playerId)) {
            messages.send(player, "island-banned");
            return;
        }
        if (!destination.open()
                && !destination.owner().equals(playerId)
                && !destination.members().containsKey(playerId)
                && !destination.coops().contains(playerId)
                && !islandService.hasBypass(playerId)) {
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

    private void invite(Player player, String[] args) {
        if (args.length != 2) {
            messages.send(player, "invite-usage");
            return;
        }
        IslandData island = ownedIsland(player);
        if (island == null) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(player, "invite-player-not-found");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.send(player, "invite-already-member");
            return;
        }
        islandService.invite(player, target, island);
    }

    private void accept(Player player, String[] args) {
        if (args.length != 2) {
            messages.send(player, "accept-usage");
            return;
        }
        islandService.acceptInvite(player, args[1]);
    }

    private void coop(Player player, String[] args) {
        if (args.length != 2) {
            messages.send(player, "coop-usage");
            return;
        }
        IslandData island = ownedIsland(player);
        if (island == null) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(player, "coop-player-not-found");
            return;
        }
        UUID targetId = target.getUniqueId();
        if (targetId.equals(player.getUniqueId())) {
            messages.send(player, "trust-self");
            return;
        }
        if (island.bannedPlayers().contains(targetId)) {
            messages.send(player, "invite-banned");
            return;
        }
        boolean add = !island.coops().contains(targetId);
        try {
            repository.replace(island.withCoop(targetId, add));
        } catch (IOException exception) {
            saveFailure(player, island, exception);
            return;
        }
        messages.send(player, add ? "coop-added" : "coop-removed", Map.of("name", target.getName()));
    }

    private void setOpen(Player player, String[] args, boolean open) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        IslandData island = ownedIsland(player);
        if (island == null) {
            return;
        }
        if (island.open() == open) {
            messages.send(player, open ? "island-already-open" : "island-already-closed");
            return;
        }
        try {
            repository.replace(island.withOpen(open));
        } catch (IOException exception) {
            saveFailure(player, island, exception);
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
        } else if (!player.hasPermission("thecore.bypass")) {
            messages.send(player, "bypass-no-permission");
        } else {
            boolean enabled = islandService.toggleBypass(player.getUniqueId());
            messages.send(player, enabled ? "bypass-enabled" : "bypass-disabled");
        }
    }

    private void openSettings(Player player, String[] args) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        Optional<IslandData> island = repository.byOwner(player.getUniqueId());
        if (island.isEmpty()) {
            messages.send(player, repository.byMember(player.getUniqueId()).isPresent()
                    ? "settings-owner-only"
                    : "island-not-created");
            return;
        }
        menus.openSettings(player, island.get());
    }

    private void openMembers(Player player, String[] args) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        Optional<IslandData> island = repository.forPlayer(player.getUniqueId());
        if (island.isEmpty()) {
            messages.send(player, "island-not-created");
        } else {
            menus.openMembers(player, island.get(), 0);
        }
    }

    private void delete(Player player, String[] args) {
        if (args.length != 1) {
            messages.send(player, "usage");
            return;
        }
        IslandData island = ownedIsland(player);
        if (island != null) {
            menus.openDeleteConfirmation(player, island);
            messages.send(player, "delete-confirm-opened");
        }
    }

    private void leave(Player player, String[] args) {
        if (args.length != 1) {
            messages.send(player, "leave-usage");
            return;
        }
        islandService.leaveIsland(player);
    }

    private enum MemberAction {
        KICK,
        BAN,
        UNBAN
    }

    private void memberAction(Player player, String[] args, MemberAction action) {
        if (args.length != 2) {
            messages.send(player, "member-usage");
            return;
        }
        IslandData island = ownedIsland(player);
        if (island == null) {
            return;
        }
        UUID targetId = resolvePlayerId(args[1], island);
        if (targetId == null && action == MemberAction.BAN
                && args[1].matches("[A-Za-z0-9_]{3,16}")) {
            targetId = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        }
        if (targetId == null) {
            messages.send(player, "member-not-found");
            return;
        }
        if (targetId.equals(island.owner())) {
            messages.send(player, action == MemberAction.BAN ? "member-cannot-ban-owner" : "member-cannot-kick-owner");
            return;
        }
        if (targetId.equals(player.getUniqueId())) {
            messages.send(player, "member-self-action");
            return;
        }
        if (action == MemberAction.KICK && !island.members().containsKey(targetId)) {
            messages.send(player, "member-not-found");
            return;
        }
        if (action == MemberAction.UNBAN && !island.bannedPlayers().contains(targetId)) {
            messages.send(player, "member-not-banned");
            return;
        }
        try {
            IslandData updated = switch (action) {
                case KICK -> island.withMember(targetId, false, 0).withCoop(targetId, false);
                case BAN -> island.withBanned(targetId, true);
                case UNBAN -> island.withBanned(targetId, false);
            };
            repository.replace(updated);
        } catch (IOException exception) {
            saveFailure(player, island, exception);
            return;
        }
        if (action == MemberAction.BAN) {
            islandService.cancelInvite(targetId, island.owner());
        }
        String targetName = Optional.ofNullable(Bukkit.getOfflinePlayer(targetId).getName()).orElse(args[1]);
        if (action == MemberAction.KICK || action == MemberAction.BAN) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null && islandService.islandAt(target.getLocation())
                    .filter(current -> current.owner().equals(island.owner())).isPresent()) {
                islandService.ejectFromIsland(target, island,
                        action == MemberAction.BAN ? "member-banned-notice" : "member-kicked-notice");
            }
        }
        messages.send(player, switch (action) {
            case KICK -> "member-kicked";
            case BAN -> "member-banned";
            case UNBAN -> "member-unbanned";
        }, Map.of("name", targetName));
    }

    private UUID resolvePlayerId(String name, IslandData island) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        List<UUID> known = new ArrayList<>();
        known.addAll(island.members().keySet());
        known.addAll(island.coops());
        known.addAll(island.bannedPlayers());
        return known.stream()
                .filter(id -> name.equalsIgnoreCase(
                        Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse("")))
                .findFirst()
                .orElseGet(() -> {
                    if (island.bannedPlayers().stream().anyMatch(id -> id.toString().equalsIgnoreCase(name))) {
                        return UUID.fromString(name);
                    }
                    return null;
                });
    }

    private IslandData ownedIsland(Player player) {
        Optional<IslandData> island = repository.byOwner(player.getUniqueId());
        if (island.isEmpty()) {
            messages.send(player, repository.byMember(player.getUniqueId()).isPresent()
                    ? "members-owner-only"
                    : "island-not-created");
            return null;
        }
        return island.get();
    }

    private void saveFailure(Player player, IslandData island, IOException exception) {
        plugin.getLogger().severe("Could not save island '" + island.name() + "': " + exception.getMessage());
        messages.send(player, "data-save-failed");
    }
}
