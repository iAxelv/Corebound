package com.corebound.thecore.island;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.message.Messages;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.structure.Structure;
import net.kyori.adventure.text.Component;

public final class IslandService {
    private static final String SCHEMATIC_ERROR =
            "No se encontró el archivo de estructura base en plugins/Corebound/schematics/. Abortando creación.";

    private final TheCorePlugin plugin;
    private final World world;
    private final IslandRepository repository;
    private final Messages messages;
    private final IslandSounds sounds;
    private final IslandPermissions permissions;
    private final Set<UUID> pendingOwners = new HashSet<>();
    private final Set<String> pendingNames = new HashSet<>();
    private final Set<UUID> islandBypass = new HashSet<>();
    private final Map<UUID, IslandInvite> invites = new HashMap<>();
    private final Set<UUID> islandChats = ConcurrentHashMap.newKeySet();
    private final Set<UUID> deletingIslands = new HashSet<>();

    private record IslandInvite(UUID inviter, UUID islandOwner, String islandName, BukkitTask expiry) {
    }

    public IslandService(
            TheCorePlugin plugin,
            World world,
            IslandRepository repository,
            Messages messages,
            IslandSounds sounds) {
        this.plugin = plugin;
        this.world = world;
        this.repository = repository;
        this.messages = messages;
        this.sounds = sounds;
        this.permissions = IslandPermissions.load(
                plugin.getDataFolder().toPath().resolve("is/is_perms.yml").toFile());
    }

    public boolean create(Player player, String islandName) {
        if (!islandName.matches("[A-Za-z0-9_]{3,16}")) {
            messages.send(player, "invalid-island-name");
            return false;
        }
        UUID owner = player.getUniqueId();
        String normalizedName = islandName.toLowerCase(Locale.ROOT);
        if (repository.forPlayer(owner).isPresent() || pendingOwners.contains(owner)) {
            messages.send(player, repository.byMember(owner).isPresent() ? "already-island-member" : "already-has-island");
            return false;
        }
        if (repository.byName(islandName).isPresent() || pendingNames.contains(normalizedName)) {
            messages.send(player, "island-name-taken");
            return false;
        }

        String schematicName = plugin.getConfig().getString("world.default-schematic", "default.schem");
        Path schematicsFolder = new File(plugin.getDataFolder(), "schematics").toPath().toAbsolutePath().normalize();
        Path schematicPath = schematicsFolder.resolve(schematicName).normalize();
        if (!schematicPath.startsWith(schematicsFolder)) {
            messages.send(player, "schematic-format");
            return false;
        }
        File schematic = schematicPath.toFile();
        if (!schematic.isFile()) {
            plugin.getLogger().severe(SCHEMATIC_ERROR);
            messages.send(player, "schematic-missing");
            return false;
        }
        String lowerName = schematic.getName().toLowerCase(Locale.ROOT);
        boolean isNbt = lowerName.endsWith(".nbt");
        boolean isSchematic = lowerName.endsWith(".schem");
        if (!isNbt && !isSchematic) {
            messages.send(player, "schematic-format");
            return false;
        }

        Structure structure = null;
        try {
            if (isNbt) {
                structure = Bukkit.getStructureManager().loadStructure(schematic);
            }
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().severe("Could not load island structure '" + schematic.getName() + "': "
                    + exception.getMessage());
            messages.send(player, "structure-load-failed");
            return false;
        }
        if (isNbt && structure == null) {
            plugin.getLogger().severe("Paper returned no structure for " + schematic.getAbsolutePath() + ".");
            messages.send(player, "structure-load-failed");
            return false;
        }
        pendingOwners.add(owner);
        pendingNames.add(normalizedName);
        long index;
        try {
            index = repository.reserveNextIndex();
        } catch (IOException exception) {
            releasePending(owner, normalizedName);
            plugin.getLogger().severe("Could not reserve an island grid slot: " + exception.getMessage());
            messages.send(player, "data-save-failed");
            return false;
        }
        IslandCoordinates coordinates = IslandGrid.coordinates(
                index, plugin.getConfig().getLong("world.distance-between-islands", 3000));
        if (isSchematic) {
            try {
                pasteSchematicAsync(owner, islandName, normalizedName, index, coordinates, schematic);
            } catch (IllegalArgumentException | IllegalStateException exception) {
                releasePending(owner, normalizedName);
                plugin.getLogger().severe("Could not schedule island structure paste: " + exception.getMessage());
                messages.send(player, "structure-place-failed");
                return false;
            }
            messages.send(player, "island-creating", java.util.Map.of("name", islandName));
            return true;
        }

        try {
            structure.place(
                    new Location(world, coordinates.x(), 100, coordinates.z()),
                    true,
                    StructureRotation.NONE,
                    Mirror.NONE,
                    0,
                    1.0f,
                    new Random());
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().severe("Could not place island structure '" + schematic.getName() + "': "
                    + exception.getMessage());
            messages.send(player, "structure-place-failed");
            releasePending(owner, normalizedName);
            return false;
        }
        try {
            return finishCreation(owner, islandName, index, coordinates);
        } finally {
            releasePending(owner, normalizedName);
        }
    }

    private void pasteSchematicAsync(
            UUID owner, String islandName, String normalizedName, long index,
            IslandCoordinates coordinates, File schematic) {
        com.sk89q.worldedit.world.World editWorld = BukkitAdapter.adapt(world);
        BlockVector3 target = BlockVector3.at(
                Math.toIntExact(coordinates.x()), 100, Math.toIntExact(coordinates.z()));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ClipboardFormat format = ClipboardFormats.findByFile(schematic);
                if (format == null) {
                    throw new IOException("No WorldEdit clipboard format recognized " + schematic.getName() + ".");
                }

                Clipboard clipboard;
                try (ClipboardReader reader = format.getReader(Files.newInputStream(schematic.toPath()))) {
                    clipboard = reader.read();
                }

                try (EditSession editSession = WorldEdit.getInstance().newEditSession(editWorld)) {
                    Operation operation = new ClipboardHolder(clipboard)
                            .createPaste(editSession)
                            .to(target)
                            .ignoreAirBlocks(false)
                            .build();
                    Operations.complete(operation);
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        finishCreation(owner, islandName, index, coordinates);
                    } finally {
                        releasePending(owner, normalizedName);
                    }
                });
            } catch (IOException | WorldEditException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not asynchronously paste island structure '" + islandName + "'.",
                        exception);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(owner);
                    if (player != null) {
                        messages.send(player, "structure-place-failed");
                    }
                    releasePending(owner, normalizedName);
                });
            }
        });
    }

    private boolean finishCreation(UUID owner, String islandName, long index, IslandCoordinates coordinates) {
        IslandData island = new IslandData(
                owner, islandName, index, coordinates.x(), coordinates.z(), true, System.currentTimeMillis(),
                java.util.Map.of(), java.util.Map.of(), java.util.Set.of(), java.util.Set.of(),
                IslandSettings.defaults(), permissions.defaultsForIsland(), java.util.Map.of(),
                java.util.Map.of(), null);
        try {
            repository.add(island);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not persist island '" + islandName + "': " + exception.getMessage());
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                messages.send(player, "data-save-failed");
            }
            return false;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            sounds.play(player, "sounds.island-created");
        }
        if (player != null && !teleport(player, island)) {
            messages.send(player, "teleport-failed");
            return true;
        }
        if (player != null) {
            messages.send(player, "island-created", java.util.Map.of("name", islandName));
        }
        return true;
    }

    private void releasePending(UUID owner, String normalizedName) {
        pendingOwners.remove(owner);
        pendingNames.remove(normalizedName);
    }

    public Optional<IslandData> islandAt(Location location) {
        if (location.getWorld() == null || !location.getWorld().getUID().equals(world.getUID())) {
            return Optional.empty();
        }

        double radius = plugin.getConfig().getDouble("world.island-radius", 35);
        return repository.all().stream()
                .filter(island -> {
                    double deltaX = Math.abs(location.getBlockX() - island.centerX());
                    double deltaZ = Math.abs(location.getBlockZ() - island.centerZ());
                    return deltaX <= radius && deltaZ <= radius;
                })
                .findFirst();
    }

    public boolean toggleBypass(UUID playerId) {
        if (islandBypass.remove(playerId)) {
            return false;
        }
        islandBypass.add(playerId);
        return true;
    }

    public boolean hasBypass(UUID playerId) {
        return islandBypass.contains(playerId);
    }

    public Optional<IslandData> islandForMember(UUID playerId) {
        return repository.forPlayer(playerId);
    }

    public boolean hasPermission(IslandData island, UUID playerId, IslandPermission permission) {
        return island.hasPermission(playerId, permission);
    }

    public boolean setPermissionMinimum(
            Player actor, IslandData island, IslandPermission permission, IslandRank rank) {
        try {
            repository.replace(island.withPermissionMinimum(permission, rank));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not update " + permission.configKey() + " permission for island '"
                    + island.name() + "': " + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        logPermissionChange(actor, island, "island-permission-audit-minimum", Map.of(
                "permission", messages.text("permission-" + permission.configKey()),
                "rank", messages.legacy(rank.messageKey())));
        return true;
    }

    public boolean setPermissionOverride(
            Player actor, IslandData island, UUID target, IslandPermission permission, boolean enabled) {
        try {
            repository.replace(island.withPermissionOverride(target, permission, enabled));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not update an individual permission for island '"
                    + island.name() + "': " + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        logPermissionChange(actor, island, "island-permission-audit-user", Map.of(
                "permission", messages.text("permission-" + permission.configKey()),
                "target", playerName(target),
                "status", messages.text(enabled ? "permission-enabled" : "permission-disabled")));
        return true;
    }

    public boolean setAllPermissionsOverride(
            Player actor, IslandData island, UUID target, boolean enabled) {
        try {
            repository.replace(island.withAllPermissions(target, enabled));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not update all individual permissions for island '"
                    + island.name() + "': " + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        logPermissionChange(actor, island, "island-permission-audit-all", Map.of(
                "target", playerName(target),
                "status", messages.text(enabled ? "permission-enabled" : "permission-disabled")));
        return true;
    }

    private void logPermissionChange(
            Player actor, IslandData island, String auditKey, Map<String, String> replacements) {
        if (actor.getUniqueId().equals(island.owner())) {
            return;
        }
        Player owner = Bukkit.getPlayer(island.owner());
        if (owner != null) {
            Map<String, String> auditReplacements = new HashMap<>(replacements);
            auditReplacements.put("user", actor.getName());
            messages.sendWithoutPrefix(owner, auditKey, auditReplacements);
        }
    }

    public boolean setSpawn(Player actor, IslandData island) {
        if (islandAt(actor.getLocation())
                .filter(current -> current.owner().equals(island.owner()))
                .isEmpty()) {
            messages.send(actor, "island-spawn-must-be-on-island");
            return false;
        }
        try {
            repository.replace(island.withSpawn(IslandSpawn.from(actor.getLocation())));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not set spawn for island '" + island.name() + "': "
                    + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        messages.send(actor, "island-spawn-set");
        return true;
    }

    public boolean teleportHome(Player player, IslandData island) {
        if (isDeleting(island.owner())) {
            return false;
        }
        Location destination = island.spawn() == null
                ? new Location(world, island.centerX() + 0.5, 102, island.centerZ() + 0.5)
                : island.spawn().toLocation(world);
        boolean teleported = player.teleport(destination);
        if (teleported) {
            applyBorder(player, island);
        }
        return teleported;
    }

    public boolean renameIsland(Player actor, IslandData island, String name) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            messages.send(actor, "invalid-island-name");
            return false;
        }
        if (!island.name().equalsIgnoreCase(name) && repository.byName(name).isPresent()) {
            messages.send(actor, "island-name-taken");
            return false;
        }
        try {
            repository.replace(island.withName(name));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not rename island '" + island.name() + "': "
                    + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        messages.send(actor, "island-renamed", Map.of("name", name));
        broadcastIslandMessage(island.withName(name), actor.getUniqueId(), "island-renamed-broadcast",
                Map.of("actor", actor.getName(), "name", name));
        return true;
    }

    public void broadcastIslandMessage(
            IslandData island, UUID excludedPlayer, String messageKey, Map<String, String> replacements) {
        Set<UUID> recipients = new HashSet<>(island.members().keySet());
        recipients.add(island.owner());
        recipients.remove(excludedPlayer);
        for (UUID recipientId : recipients) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient != null) {
                messages.send(recipient, messageKey, replacements);
            }
        }
    }

    public boolean transferOwnership(Player actor, IslandData island, UUID newOwnerId) {
        if (!island.owner().equals(actor.getUniqueId()) || !island.members().containsKey(newOwnerId)
                || island.owner().equals(newOwnerId)) {
            messages.send(actor, "transfer-invalid-target");
            return false;
        }
        IslandData updated = island.withOwner(newOwnerId, System.currentTimeMillis());
        try {
            repository.transferOwnership(island.owner(), updated);
        } catch (IOException | IllegalStateException exception) {
            plugin.getLogger().severe("Could not transfer island '" + island.name() + "' to "
                    + newOwnerId + ": " + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        cancelInvitesForIsland(island.owner());
        Player newOwner = Bukkit.getPlayer(newOwnerId);
        String oldOwnerName = actor.getName();
        if (newOwner != null) {
            messages.send(newOwner, "island-transfer-received", Map.of("name", oldOwnerName));
        }
        messages.send(actor, "island-transfer-sent", Map.of(
                "name", playerName(newOwnerId),
                "rank", messages.text(IslandRank.ADMINISTRATOR.messageKey())));
        return true;
    }

    public boolean changeMemberRank(Player actor, IslandData island, UUID targetId, boolean promote) {
        if (targetId.equals(island.owner()) || !island.members().containsKey(targetId)) {
            messages.send(actor, "member-not-found");
            return false;
        }
        IslandRank actorRank = island.rank(actor.getUniqueId());
        IslandRank targetRank = island.rank(targetId);
        if (actorRank.level() <= targetRank.level()) {
            messages.send(actor, "rank-action-higher-only");
            return false;
        }
        if (promote && targetRank.level() >= IslandRank.ADMINISTRATOR.level()) {
            messages.send(actor, "rank-already-highest");
            return false;
        }
        if (!promote && targetRank == IslandRank.MEMBER) {
            messages.send(actor, "rank-already-lowest");
            return false;
        }
        IslandRank updatedRank = IslandRank.values()[targetRank.ordinal() + (promote ? 1 : -1)];
        try {
            repository.replace(island.withRank(targetId, updatedRank));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not change rank of " + playerName(targetId) + " on island '"
                    + island.name() + "': " + exception.getMessage());
            messages.send(actor, "data-save-failed");
            return false;
        }
        String userName = playerName(targetId);
        for (UUID memberId : island.members().keySet()) {
            Player recipient = Bukkit.getPlayer(memberId);
            if (recipient != null) {
                messages.send(recipient, "island-member-rank-changed", Map.of(
                        "actor", actor.getName(),
                        "user", userName,
                        "rank", messages.legacy(updatedRank.messageKey())));
            }
        }
        Player owner = Bukkit.getPlayer(island.owner());
        if (owner != null) {
            messages.send(owner, "island-member-rank-changed", Map.of(
                    "actor", actor.getName(),
                    "user", userName,
                    "rank", messages.legacy(updatedRank.messageKey())));
        }
        return true;
    }

    private String playerName(UUID playerId) {
        return Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName())
                .orElse(playerId.toString());
    }

    public boolean toggleIslandChat(Player player) {
        UUID playerId = player.getUniqueId();
        Optional<IslandData> found = repository.forPlayer(playerId);
        if (found.isEmpty()) {
            messages.send(player, "island-not-created");
            return false;
        }
        if (!islandChats.add(playerId)) {
            islandChats.remove(playerId);
            messages.send(player, "island-chat-disabled");
            return false;
        }
        messages.send(player, "island-chat-enabled");
        return true;
    }

    public void clearIslandChat(UUID playerId) {
        islandChats.remove(playerId);
    }

    public boolean isIslandChatEnabled(UUID playerId) {
        return islandChats.contains(playerId);
    }

    public void sendIslandChat(UUID senderId, Component message) {
        Player sender = Bukkit.getPlayer(senderId);
        IslandData island = repository.forPlayer(senderId).orElse(null);
        if (sender == null || island == null
                || (!island.owner().equals(senderId) && !island.members().containsKey(senderId))) {
            islandChats.remove(senderId);
            return;
        }
        Component formatted = messages.format("island-chat-format", Map.of(
                "rank", messages.component(island.rank(senderId).messageKey()),
                "username", Component.text(sender.getName()),
                "message", message));
        Set<UUID> recipients = new HashSet<>(island.members().keySet());
        recipients.add(island.owner());
        recipients.stream()
                .map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull)
                .forEach(recipient -> recipient.sendMessage(formatted));
    }

    public boolean isDeleting(UUID islandOwner) {
        return deletingIslands.contains(islandOwner);
    }

    public void clearBypass(UUID playerId) {
        islandBypass.remove(playerId);
    }

    public void invite(Player inviter, Player target, IslandData island) {
        if (isDeleting(island.owner())) {
            messages.send(inviter, "island-being-deleted");
            return;
        }
        UUID targetId = target.getUniqueId();
        if (targetId.equals(island.owner()) || island.members().containsKey(targetId)) {
            messages.send(inviter, "invite-already-member");
            return;
        }
        if (island.bannedPlayers().contains(targetId)) {
            messages.send(inviter, "invite-banned");
            return;
        }
        if (repository.forPlayer(targetId).isPresent()) {
            messages.send(inviter, "invite-has-island");
            return;
        }
        IslandInvite previous = invites.remove(targetId);
        if (previous != null) {
            previous.expiry().cancel();
        }
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            IslandInvite expired = invites.remove(targetId);
            if (expired == null) {
                return;
            }
            Player invited = Bukkit.getPlayer(targetId);
            if (invited != null) {
                messages.send(invited, "invite-expired-target", Map.of("name", expired.islandName()));
            }
            Player owner = Bukkit.getPlayer(expired.inviter());
            if (owner != null) {
                messages.send(owner, "invite-expired-owner", Map.of("name", invited == null
                        ? target.getName()
                        : invited.getName()));
            }
        }, 6000L);
        invites.put(targetId, new IslandInvite(inviter.getUniqueId(), island.owner(), island.name(), expiry));
        messages.send(inviter, "invite-sent", Map.of("name", target.getName()));
        messages.send(target, "invite-received", Map.of("name", inviter.getName()));
        sounds.play(target, "sounds.invite-received");
    }

    public boolean acceptInvite(Player player, String inviterName) {
        IslandInvite invite = invites.get(player.getUniqueId());
        if (invite == null || !invite.islandName().equalsIgnoreCase(inviterName)
                && !Optional.ofNullable(Bukkit.getOfflinePlayer(invite.islandOwner()).getName())
                        .map(name -> name.equalsIgnoreCase(inviterName)).orElse(false)) {
            messages.send(player, "invite-not-found");
            return false;
        }
        if (repository.forPlayer(player.getUniqueId()).isPresent()) {
            messages.send(player, repository.byMember(player.getUniqueId()).isPresent()
                    ? "already-island-member"
                    : "already-has-island");
            return false;
        }
        Optional<IslandData> found = repository.byOwner(invite.islandOwner());
        if (found.isEmpty() || isDeleting(invite.islandOwner())
                || found.get().bannedPlayers().contains(player.getUniqueId())) {
            invites.remove(player.getUniqueId());
            invite.expiry().cancel();
            messages.send(player, "invite-no-longer-valid");
            return false;
        }
        IslandData island = found.get();
        try {
            repository.replace(island.withMember(player.getUniqueId(), true, System.currentTimeMillis()));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not add " + player.getName() + " to island '" + island.name()
                    + "': " + exception.getMessage());
            messages.send(player, "data-save-failed");
            return false;
        }
        invites.remove(player.getUniqueId());
        invite.expiry().cancel();
        messages.send(player, "invite-accepted", Map.of("name", island.name()));
        Player inviter = Bukkit.getPlayer(invite.inviter());
        if (inviter != null) {
            messages.send(inviter, "invite-accepted-owner", Map.of("name", player.getName()));
        }
        if (!teleportHome(player, island)) {
            plugin.getLogger().warning("Could not teleport " + player.getName()
                    + " to island '" + island.name() + "' after accepting an invite.");
            messages.send(player, "teleport-failed");
        } else {
            sounds.play(player, "sounds.member-joined");
        }
        return true;
    }

    public List<String> pendingInviterNames(UUID playerId) {
        IslandInvite invite = invites.get(playerId);
        if (invite == null) {
            return List.of();
        }
        String inviterName = Optional.ofNullable(Bukkit.getOfflinePlayer(invite.inviter()).getName())
                .orElse(invite.islandName());
        return List.of(inviterName);
    }

    public boolean leaveIsland(Player player) {
        UUID playerId = player.getUniqueId();
        Optional<IslandData> found = repository.forPlayer(playerId);
        if (found.isEmpty()) {
            messages.send(player, "island-not-created");
            return false;
        }
        IslandData island = found.get();
        if (island.owner().equals(playerId)) {
            messages.send(player, "leave-owner-only");
            return false;
        }
        try {
            repository.replace(island.withMember(playerId, false, 0));
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not remove " + player.getName() + " from island '"
                    + island.name() + "': " + exception.getMessage());
            messages.send(player, "data-save-failed");
            return false;
        }
        clearIslandChat(playerId);
        Map<UUID, Player> recipients = new HashMap<>();
        Player owner = Bukkit.getPlayer(island.owner());
        if (owner != null) {
            recipients.put(owner.getUniqueId(), owner);
        }
        island.members().keySet().stream()
                .filter(memberId -> !memberId.equals(playerId))
                .map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull)
                .forEach(member -> recipients.put(member.getUniqueId(), member));
        recipients.values().forEach(recipient ->
                messages.send(recipient, "island-member-left", Map.of("user", player.getName())));
        ejectFromIsland(player, island, "island-left");
        return true;
    }

    public void cancelInvite(UUID playerId) {
        IslandInvite invite = invites.remove(playerId);
        if (invite != null) {
            invite.expiry().cancel();
        }
    }

    public void cancelInvite(UUID playerId, UUID islandOwner) {
        IslandInvite invite = invites.get(playerId);
        if (invite != null && invite.islandOwner().equals(islandOwner)) {
            invites.remove(playerId);
            invite.expiry().cancel();
            Player target = Bukkit.getPlayer(playerId);
            if (target != null) {
                messages.send(target, "invite-no-longer-valid");
            }
        }
    }

    private void cancelInvitesForIsland(UUID islandOwner) {
        List<UUID> invitedPlayers = invites.entrySet().stream()
                .filter(entry -> entry.getValue().islandOwner().equals(islandOwner))
                .map(Map.Entry::getKey)
                .toList();
        for (UUID invitedPlayer : invitedPlayers) {
            cancelInvite(invitedPlayer, islandOwner);
        }
    }

    public boolean isBannedFromIsland(UUID playerId, IslandData island) {
        return island.bannedPlayers().contains(playerId);
    }

    public void ejectFromIsland(Player player, IslandData island, String messageKey) {
        ejectFromIsland(player, island, messageKey, Map.of());
    }

    public void ejectFromIsland(
            Player player, IslandData island, String messageKey, Map<String, String> replacements) {
        Optional<IslandData> home = repository.byOwner(player.getUniqueId())
                .filter(homeIsland -> !homeIsland.owner().equals(island.owner()));
        boolean teleported = home.isPresent() && teleportHome(player, home.get());
        if (!teleported) {
            World spawnWorld = Bukkit.getWorlds().stream()
                    .filter(candidate -> !candidate.getUID().equals(world.getUID()))
                    .findFirst()
                    .orElse(world);
            teleported = player.teleport(spawnWorld.getSpawnLocation());
            if (teleported) {
                player.setWorldBorder(null);
            }
        }
        if (teleported) {
            messages.send(player, messageKey, replacements);
        } else {
            plugin.getLogger().warning("Could not eject " + player.getName() + " from island '" + island.name() + "'.");
            messages.send(player, "teleport-failed");
        }
    }

    public void deleteIsland(IslandData island, Consumer<Boolean> completion) {
        if (!deletingIslands.add(island.owner())) {
            completion.accept(false);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            islandAt(player.getLocation())
                    .filter(current -> current.owner().equals(island.owner()))
                    .ifPresent(current -> ejectFromIsland(player, current, "island-deleted-ejected"));
        }

        com.sk89q.worldedit.world.World editWorld = BukkitAdapter.adapt(world);
        int radius = (int) Math.ceil(plugin.getConfig().getDouble("world.island-radius", 35));
        BlockVector3 minimum = BlockVector3.at(
                Math.toIntExact(island.centerX() - radius), world.getMinHeight(),
                Math.toIntExact(island.centerZ() - radius));
        BlockVector3 maximum = BlockVector3.at(
                Math.toIntExact(island.centerX() + radius), world.getMaxHeight() - 1,
                Math.toIntExact(island.centerZ() + radius));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (EditSession editSession = WorldEdit.getInstance().newEditSession(editWorld)) {
                editSession.setBlocks(new CuboidRegion(editWorld, minimum, maximum),
                        BlockTypes.AIR.getDefaultState());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        repository.remove(island.owner());
                        cancelInvitesForIsland(island.owner());
                        completion.accept(true);
                    } catch (IOException exception) {
                        plugin.getLogger().severe("Could not delete island '" + island.name() + "': "
                                + exception.getMessage());
                        completion.accept(false);
                    } finally {
                        deletingIslands.remove(island.owner());
                    }
                });
            } catch (WorldEditException | RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not clear island '" + island.name() + "'.", exception);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    deletingIslands.remove(island.owner());
                    completion.accept(false);
                });
            }
        });
    }

    public void ejectVisitors(IslandData island) {
        for (Player visitor : Bukkit.getOnlinePlayers()) {
            if (island.owner().equals(visitor.getUniqueId())
                    || island.members().containsKey(visitor.getUniqueId())
                    || island.coops().contains(visitor.getUniqueId())
                    || hasBypass(visitor.getUniqueId())
                    || islandAt(visitor.getLocation())
                            .filter(currentIsland -> currentIsland.owner().equals(island.owner()))
                            .isEmpty()) {
                continue;
            }

            Optional<IslandData> home = repository.byOwner(visitor.getUniqueId());
            boolean teleported = home.isPresent() && teleportHome(visitor, home.get());
            if (!teleported) {
                World spawnWorld = Bukkit.getWorlds().stream()
                        .filter(candidate -> !candidate.getUID().equals(world.getUID()))
                        .findFirst()
                        .orElse(world);
                teleported = visitor.teleport(spawnWorld.getSpawnLocation());
                if (teleported) {
                    visitor.setWorldBorder(null);
                }
            }

            if (teleported) {
                messages.send(visitor, "island-visitor-ejected");
            } else {
                plugin.getLogger().warning("Could not eject visitor " + visitor.getName()
                        + " from closed island '" + island.name() + "'.");
                messages.send(visitor, "teleport-failed");
            }
        }
    }

    public boolean teleport(Player player, IslandData island) {
        if (isDeleting(island.owner())) {
            return false;
        }
        boolean teleported = player.teleport(new Location(world, island.centerX() + 0.5, 102, island.centerZ() + 0.5));
        if (!teleported) {
            return false;
        }
        applyBorder(player, island);
        return true;
    }

    public void updateBorder(Player player) {
        updateBorder(player, player.getLocation());
    }

    public void updateBorder(Player player, Location location) {
        updateBorder(player, location, false);
    }

    public void refreshBorder(Player player) {
        updateBorder(player, player.getLocation(), true);
    }

    private void updateBorder(Player player, Location location, boolean force) {
        if (location.getWorld() == null || !location.getWorld().getUID().equals(world.getUID())) {
            if (player.getWorldBorder() != null) {
                player.setWorldBorder(null);
            }
            return;
        }

        WorldBorder currentBorder = player.getWorldBorder();
        Optional<IslandData> island = islandWithinBorder(location);
        if (island.isEmpty()) {
            // Keep the island border active at its edge; it is what prevents leaving the island.
            return;
        }

        IslandData currentIsland = island.get();
        double centerX = currentIsland.centerX() + 0.5;
        double centerZ = currentIsland.centerZ() + 0.5;
        double size = plugin.getConfig().getDouble("world.island-radius", 35) * 2;
        if (!force && currentBorder != null
                && Math.abs(currentBorder.getCenter().getX() - centerX) < 0.01
                && Math.abs(currentBorder.getCenter().getZ() - centerZ) < 0.01
                && Math.abs(currentBorder.getSize() - size) < 0.01) {
            return;
        }
        applyBorder(player, currentIsland);
    }

    private boolean isIslandBorder(WorldBorder border) {
        if (border == null) {
            return false;
        }
        double size = plugin.getConfig().getDouble("world.island-radius", 35) * 2;
        return repository.all().stream().anyMatch(island ->
                Math.abs(border.getCenter().getX() - island.centerX() - 0.5) < 0.01
                        && Math.abs(border.getCenter().getZ() - island.centerZ() - 0.5) < 0.01
                        && Math.abs(border.getSize() - size) < 0.01);
    }

    private Optional<IslandData> islandWithinBorder(Location location) {
        double radius = plugin.getConfig().getDouble("world.island-radius", 35);
        return repository.all().stream()
                .filter(island -> Math.abs(location.getX() - island.centerX() - 0.5) <= radius
                        && Math.abs(location.getZ() - island.centerZ() - 0.5) <= radius)
                .findFirst();
    }

    public World islandWorld() {
        return world;
    }

    private void applyBorder(Player player, IslandData island) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(island.centerX() + 0.5, island.centerZ() + 0.5);
        border.setSize(plugin.getConfig().getDouble("world.island-radius", 35) * 2);
        player.setWorldBorder(border);
    }
}
