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
import com.sk89q.worldedit.session.ClipboardHolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;

public final class IslandService {
    private static final String SCHEMATIC_ERROR =
            "No se encontró el archivo de estructura base en plugins/TheCore/schematics/. Abortando creación.";

    private final TheCorePlugin plugin;
    private final World world;
    private final IslandRepository repository;
    private final Messages messages;
    private final Set<UUID> pendingOwners = new HashSet<>();
    private final Set<String> pendingNames = new HashSet<>();

    public IslandService(
            TheCorePlugin plugin, World world, IslandRepository repository, Messages messages) {
        this.plugin = plugin;
        this.world = world;
        this.repository = repository;
        this.messages = messages;
    }

    public boolean create(Player player, String islandName) {
        if (!islandName.matches("[A-Za-z0-9_]{3,16}")) {
            messages.send(player, "invalid-island-name");
            return false;
        }
        UUID owner = player.getUniqueId();
        String normalizedName = islandName.toLowerCase(Locale.ROOT);
        if (repository.byOwner(owner).isPresent() || pendingOwners.contains(owner)) {
            messages.send(player, "already-has-island");
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
                owner, islandName, index, coordinates.x(), coordinates.z(), java.util.Set.of());
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
        double radiusSquared = radius * radius;
        return repository.all().stream()
                .filter(island -> {
                    double deltaX = location.getX() - island.centerX();
                    double deltaZ = location.getZ() - island.centerZ();
                    return deltaX * deltaX + deltaZ * deltaZ <= radiusSquared;
                })
                .findFirst();
    }

    public boolean teleport(Player player, IslandData island) {
        boolean teleported = player.teleport(new Location(world, island.centerX() + 0.5, 102, island.centerZ() + 0.5));
        if (!teleported) {
            return false;
        }
        applyBorder(player, island);
        return true;
    }

    public void updateBorder(Player player) {
        if (player.getWorld().getUID().equals(world.getUID())) {
            Optional<IslandData> currentIsland = islandAt(player.getLocation());
            if (currentIsland.isPresent()) {
                IslandData island = currentIsland.get();
                WorldBorder currentBorder = player.getWorldBorder();
                double centerX = island.centerX() + 0.5;
                double centerZ = island.centerZ() + 0.5;
                double size = plugin.getConfig().getDouble("world.island-radius", 35) * 2;
                if (currentBorder != null
                        && currentBorder.getCenter().getX() == centerX
                        && currentBorder.getCenter().getZ() == centerZ
                        && currentBorder.getSize() == size) {
                    return;
                }
                applyBorder(player, island);
            } else if (player.getWorldBorder() != null) {
                player.setWorldBorder(null);
            }
        } else if (player.getWorldBorder() != null) {
            player.setWorldBorder(null);
        }
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
