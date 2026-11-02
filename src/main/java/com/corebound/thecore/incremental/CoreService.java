package com.corebound.thecore.incremental;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CoreService {
    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final CoreRepository repository;
    private final Messages messages;
    private final YamlConfiguration config;
    private final NamespacedKey coreItemKey = new NamespacedKey("thecore", "core_item");
    private final NamespacedKey coreEntityKey;
    private final NamespacedKey islandIndexKey;
    private final Material displayMaterial;
    private final float displayScale;
    private final float interactionWidth;
    private final float interactionHeight;
    private final double displayHeightOffset;
    private final double textOffset;
    private final int textLineWidth;
    private final int rotationSpeedTicks;
    private final long comboWindowMillis;
    private final Sound hitSound;
    private final float hitSoundVolume;
    private final Set<UUID> itemDeliveries = ConcurrentHashMap.newKeySet();

    public CoreService(
            TheCorePlugin plugin,
            IslandService islandService,
            CoreRepository repository,
            Messages messages,
            File configFile) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.repository = repository;
        this.messages = messages;
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultsStream = plugin.getResource("core/core.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/core.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not migrate core/core.yml defaults.", exception);
        }
        CoreItem.create(config, messages, coreItemKey, 1);
        displayMaterial = configuredBlock("display.material", "CRYING_OBSIDIAN");
        displayScale = positiveFloat("display.scale", 1.3f);
        interactionWidth = positiveFloat("interaction.width", 1.3f);
        interactionHeight = positiveFloat("interaction.height", 1.3f);
        displayHeightOffset = config.getDouble("display.height-offset", 1.8);
        textOffset = config.getDouble("text-display.offset-y", 1.1);
        textLineWidth = config.getInt("text-display.line-width", 200);
        rotationSpeedTicks = config.getInt("display.rotation-speed-ticks", 20);
        comboWindowMillis = config.getLong("combo.window-ms", 3000L);
        if (textLineWidth <= 0 || rotationSpeedTicks <= 0 || comboWindowMillis <= 0) {
            throw new IllegalArgumentException(
                    "core.yml display rotation speed, combo window, and text line width must be positive.");
        }
        String soundKey = config.getString("sound.key", "minecraft:block.note_block.pling");
        NamespacedKey parsedSoundKey = soundKey == null ? null : NamespacedKey.fromString(soundKey);
        hitSound = parsedSoundKey == null ? null : Registry.SOUNDS.get(parsedSoundKey);
        if (hitSound == null) {
            throw new IllegalArgumentException("core.yml sound.key must be a valid registered sound.");
        }
        double configuredVolume = config.getDouble("sound.volume", 1.0);
        if (!Double.isFinite(configuredVolume) || configuredVolume < 0 || configuredVolume > Float.MAX_VALUE) {
            throw new IllegalArgumentException("core.yml sound.volume must be a non-negative finite number.");
        }
        hitSoundVolume = (float) configuredVolume;
        coreEntityKey = new NamespacedKey(plugin, "core_entity");
        islandIndexKey = new NamespacedKey(plugin, "core_island_index");
    }

    public void onIslandCreated(IslandCreatedEvent event) {
        UUID owner = event.island().owner();
        repository.markItemPending(owner);
        deliverPendingItem(owner);
    }

    public void onPlayerJoin(Player player) {
        deliverPendingItem(player.getUniqueId());
    }

    private void deliverPendingItem(UUID playerId) {
        if (!repository.hasPendingItem(playerId) || !itemDeliveries.add(playerId)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            itemDeliveries.remove(playerId);
            return;
        }
        player.getScheduler().run(plugin, task -> {
            try {
                if (!player.isOnline() || !repository.hasPendingItem(playerId)) {
                    return;
                }
                if (containsCoreItem(player)) {
                    repository.clearPendingItem(playerId);
                    return;
                }
                ItemStack coreItem = CoreItem.create(config, messages, coreItemKey, 1);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(coreItem);
                for (ItemStack leftover : overflow.values()) {
                    Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    dropped.setPersistent(true);
                }
                repository.clearPendingItem(playerId);
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not deliver the core item to " + playerId + ".", exception);
                messages.send(player, "core-item-delivery-failed");
            } finally {
                itemDeliveries.remove(playerId);
            }
        }, () -> itemDeliveries.remove(playerId));
    }

    private boolean containsCoreItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (CoreItem.isCoreItem(item, coreItemKey)) {
                return true;
            }
        }
        return CoreItem.isCoreItem(player.getInventory().getItemInOffHand(), coreItemKey);
    }

    public boolean isCoreItem(ItemStack item) {
        return CoreItem.isCoreItem(item, coreItemKey);
    }

    public boolean isCoreEntity(org.bukkit.entity.Entity entity) {
        return entity.getPersistentDataContainer().has(coreEntityKey, PersistentDataType.BYTE);
    }

    public void pickupCore(Player player, org.bukkit.entity.Entity clickedEntity) {
        Long islandIndex = clickedEntity.getPersistentDataContainer().get(islandIndexKey, PersistentDataType.LONG);
        if (islandIndex == null) {
            return;
        }
        IslandData island = islandService.islandAt(clickedEntity.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-pickup-owner-only");
            return;
        }
        CoreRecord core = repository.core(islandIndex);
        if (core == null) {
            return;
        }
        CoreRecord removed = repository.removeCore(islandIndex);
        if (removed == null) {
            return;
        }

        ItemStack coreItem = CoreItem.create(
                config, messages, coreItemKey, removed.level(), removed.energy(), removed.hits(), removed.lastHitAt());
        try {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(coreItem);
            for (ItemStack leftover : overflow.values()) {
                Location dropLocation = clickedEntity.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
                Item dropped = clickedEntity.getWorld().dropItem(dropLocation, leftover);
                dropped.setPersistent(true);
            }
            removeCoreEntities(removed);
            messages.send(player, "core-picked-up");
        } catch (RuntimeException exception) {
            repository.addCore(removed);
            throw exception;
        }
    }

    private void removeCoreEntities(CoreRecord core) {
        org.bukkit.World world = plugin.getServer().getWorld(core.worldId());
        if (world == null) {
            return;
        }
        for (UUID entityId : List.of(core.itemDisplayId(), core.interactionId(), core.textDisplayId())) {
            org.bukkit.entity.Entity entity = world.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    public boolean placeCore(Player player, org.bukkit.block.Block clickedBlock) {
        if (!clickedBlock.getType().isSolid()) {
            messages.send(player, "core-needs-solid-block");
            return false;
        }
        if (!clickedBlock.getRelative(org.bukkit.block.BlockFace.UP).getType().isAir()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP, 2).getType().isAir()) {
            messages.send(player, "core-needs-clearance");
            return false;
        }
        IslandData island = islandService.islandAt(clickedBlock.getLocation()).orElse(null);
        if (island == null) {
            messages.send(player, "core-must-place-on-island");
            return false;
        }
        if (!island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-owner-only");
            return false;
        }
        if (repository.hasCore(island.index())) {
            messages.send(player, "core-already-placed");
            return false;
        }

        Location coreLocation = new Location(
                clickedBlock.getWorld(),
                clickedBlock.getX() + 0.5,
                clickedBlock.getY() + displayHeightOffset,
                clickedBlock.getZ() + 0.5);
        ItemDisplay itemDisplay = null;
        Interaction interaction = null;
        TextDisplay textDisplay = null;
        CoreItem.Progress progress = CoreItem.progress(player.getInventory().getItemInMainHand(), coreItemKey);
        try {
            itemDisplay = clickedBlock.getWorld().spawn(coreLocation, ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(displayMaterial));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                display.setTransformation(new Transformation(
                        new Vector3f(),
                        new Quaternionf(),
                        new Vector3f(displayScale, displayScale, displayScale),
                        new Quaternionf()));
                display.setGravity(false);
                display.setInvulnerable(true);
                display.setPersistent(true);
                markCoreEntity(display, island.index());
            });
            Location interactionLocation = coreLocation.clone().subtract(0, interactionHeight / 2.0, 0);
            interaction = clickedBlock.getWorld().spawn(interactionLocation, Interaction.class, entity -> {
                entity.setInteractionWidth(interactionWidth);
                entity.setInteractionHeight(interactionHeight);
                entity.setResponsive(true);
                entity.setGravity(false);
                entity.setInvulnerable(false);
                entity.setPersistent(true);
                markCoreEntity(entity, island.index());
            });
            Location textLocation = coreLocation.clone().add(0, textOffset, 0);
            textDisplay = clickedBlock.getWorld().spawn(textLocation, TextDisplay.class, display -> {
                display.text(Component.empty());
                display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
                display.setAlignment(TextDisplay.TextAlignment.CENTER);
                display.setLineWidth(textLineWidth);
                display.setSeeThrough(true);
                display.setShadowed(true);
                display.setGravity(false);
                display.setInvulnerable(true);
                display.setPersistent(true);
                markCoreEntity(display, island.index());
            });
            repository.addCore(new CoreRecord(
                    island.index(),
                    coreLocation.getWorld().getUID(),
                    coreLocation.getX(),
                    coreLocation.getY(),
                    coreLocation.getZ(),
                    itemDisplay.getUniqueId(),
                    interaction.getUniqueId(),
                    textDisplay.getUniqueId(),
                    progress.level(),
                    progress.energy(),
                    progress.hits(),
                    progress.lastHitAt()));
        } catch (RuntimeException exception) {
            removeIfPresent(textDisplay);
            removeIfPresent(interaction);
            removeIfPresent(itemDisplay);
            plugin.getLogger().log(Level.SEVERE,
                    "Could not place the island core for island " + island.name() + ".", exception);
            messages.send(player, "core-placement-failed");
            return false;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            heldItem.setAmount(heldItem.getAmount() - 1);
        }
        startRotation(itemDisplay);
        updateHologram(textDisplay, repository.core(island.index()), System.currentTimeMillis());
        messages.send(player, "core-placed", Map.of("island", island.name()));
        return true;
    }

    public void onCoreHit(Player player, org.bukkit.entity.Entity entity) {
        Long islandIndex = entity.getPersistentDataContainer().get(islandIndexKey, PersistentDataType.LONG);
        if (islandIndex == null) {
            return;
        }
        IslandData island = islandService.islandAt(entity.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex) {
            return;
        }
        if (!island.canBuild(player.getUniqueId())) {
            messages.send(player, "core-hit-not-allowed");
            return;
        }
        long now = System.currentTimeMillis();
        CoreRecord updated = repository.registerHit(islandIndex, now, comboWindowMillis);
        if (updated == null) {
            return;
        }
        float pitch = 1.0f + Math.min(updated.hits(), 50) / 50.0f;
        entity.getWorld().playSound(entity.getLocation(), hitSound, hitSoundVolume, pitch);
        updateCoreEntities(updated, now);
        long delayTicks = Math.max(1L, (comboWindowMillis + 49L) / 50L);
        entity.getScheduler().runDelayed(plugin, task -> {
            CoreRecord expired = repository.expireCombo(islandIndex, updated.lastHitAt());
            if (expired != null) {
                updateCoreEntities(expired, System.currentTimeMillis());
            }
        }, null, delayTicks);
    }

    public void onEntityLoad(org.bukkit.entity.Entity entity) {
        Long islandIndex = entity.getPersistentDataContainer().get(islandIndexKey, PersistentDataType.LONG);
        if (islandIndex == null || !repository.hasCore(islandIndex)) {
            return;
        }
        if (entity instanceof ItemDisplay itemDisplay) {
            startRotation(itemDisplay);
        } else if (entity instanceof TextDisplay textDisplay) {
            updateHologram(textDisplay, repository.core(islandIndex), System.currentTimeMillis());
        }
    }

    public void initializeLoadedEntities() {
        for (CoreRecord core : repository.allCores()) {
            org.bukkit.World world = plugin.getServer().getWorld(core.worldId());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, core.x(), core.y(), core.z());
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                org.bukkit.entity.Entity item = world.getEntity(core.itemDisplayId());
                org.bukkit.entity.Entity text = world.getEntity(core.textDisplayId());
                if (item != null) {
                    onEntityLoad(item);
                }
                if (text != null) {
                    onEntityLoad(text);
                }
            });
        }
    }

    private void updateCoreEntities(CoreRecord core, long now) {
        org.bukkit.World world = plugin.getServer().getWorld(core.worldId());
        if (world == null) {
            return;
        }
        Location coreLocation = new Location(world, core.x(), core.y(), core.z());
        plugin.getServer().getRegionScheduler().run(plugin, coreLocation, task -> {
            org.bukkit.entity.Entity textEntity = world.getEntity(core.textDisplayId());
            if (textEntity instanceof TextDisplay textDisplay) {
                updateHologram(textDisplay, core, now);
            }
        });
    }

    private void updateHologram(TextDisplay display, CoreRecord core, long now) {
        if (core == null) {
            return;
        }
        display.getScheduler().run(plugin, task -> {
            if (!display.isValid()) {
                return;
            }
            List<Component> lines = new ArrayList<>();
            for (String line : config.getStringList("text-display.lines")) {
                String rendered = line
                        .replace("%level%", Integer.toString(core.level()))
                        .replace("%base_energy%", formatEnergy(core.baseEnergy()))
                        .replace("%energy%", formatEnergy(core.energy()))
                        .replace("%combo%", String.format(Locale.ROOT, "%.2f", core.combo(now, comboWindowMillis)))
                        .replace("%hits%", Integer.toString(
                                now - core.lastHitAt() > comboWindowMillis ? 0 : core.hits()));
                lines.add(messages.parseComponent(rendered));
            }
            Component text = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    text = text.append(Component.newline());
                }
                text = text.append(lines.get(i));
            }
            display.text(text);
        }, null);
    }

    private String formatEnergy(double energy) {
        if (energy >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", energy / 1_000_000);
        }
        if (energy >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", energy / 1_000);
        }
        return String.format(Locale.ROOT, "%.1f", energy);
    }

    private void startRotation(ItemDisplay display) {
        display.getScheduler().runAtFixedRate(plugin, new java.util.function.Consumer<>() {
            private int quarterTurn;

            @Override
            public void accept(io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
                if (!display.isValid()) {
                    task.cancel();
                    return;
                }
                Transformation current = display.getTransformation();
                Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(90 * ++quarterTurn));
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(rotationSpeedTicks);
                display.setTransformation(new Transformation(
                        new Vector3f(current.getTranslation()),
                        new Quaternionf(current.getLeftRotation()),
                        new Vector3f(current.getScale()),
                        rotation));
            }
        }, null, 1L, rotationSpeedTicks);
    }

    private void markCoreEntity(org.bukkit.entity.Entity entity, long islandIndex) {
        entity.getPersistentDataContainer().set(coreEntityKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(islandIndexKey, PersistentDataType.LONG, islandIndex);
    }

    private void removeIfPresent(org.bukkit.entity.Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private Material configuredBlock(String path, String fallback) {
        Material material = Material.matchMaterial(config.getString(path, fallback));
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("core.yml " + path + " must be a valid block material.");
        }
        return material;
    }

    private float positiveFloat(String path, float fallback) {
        double value = config.getDouble(path, fallback);
        if (!Double.isFinite(value) || value <= 0 || value > Float.MAX_VALUE) {
            throw new IllegalArgumentException("core.yml " + path + " must be a positive finite number.");
        }
        return (float) value;
    }

    public void flush() throws java.io.IOException {
        repository.flush();
    }
}
