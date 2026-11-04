package com.corebound.thecore.incremental;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.config.ConfigFiles;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
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
    private final File configFile;
    private YamlConfiguration config;
    private final NamespacedKey coreItemKey = new NamespacedKey("thecore", "core_item");
    private final NamespacedKey coreEntityKey;
    private final NamespacedKey islandIndexKey;
    private Material displayMaterial;
    private float displayScale;
    private float interactionWidth;
    private float interactionHeight;
    private double displayHeightOffset;
    private double textOffset;
    private int textLineWidth;
    private int rotationSpeedTicks;
    private long comboWindowMillis;
    private Sound hitSound;
    private float hitSoundVolume;
    private final Set<UUID> itemDeliveries = ConcurrentHashMap.newKeySet();
    private final Set<UUID> rotatingDisplays = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DisplayAnimation> displayAnimations = new ConcurrentHashMap<>();
    private final Map<Long, io.papermc.paper.threadedregions.scheduler.ScheduledTask> anomalyTasks =
            new ConcurrentHashMap<>();
    private final Map<Long, AnomalyState> anomalies = new ConcurrentHashMap<>();
    private final CoreMenu coreMenu;
    private final CoreUpgradesMenu coreUpgradesMenu;
    private final AstralTreeMenu astralTreeMenu;
    private PylonService pylonService;
    private CondenserService condenserService;
    private CropGeneratorService cropGeneratorService;

    private static final class DisplayAnimation {
        private float yaw;
        private float currentScale;
        private float targetScale;
        private int scaleTransitionTicks;

        private DisplayAnimation(float scale) {
            currentScale = scale;
            targetScale = scale;
        }
    }

    private static final class AnomalyState {
        private volatile long unstableUntil;
        private volatile int hits;
        private volatile int targetHits;
        private volatile long hyperdriveUntil;
        private volatile long nextAnomalyAllowedAt;
    }

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
        this.configFile = configFile;
        YamlConfiguration initialConfig = loadConfiguration(true);
        CoreItem.create(initialConfig, messages, coreItemKey, 1);
        applyConfiguration(initialConfig);
        coreEntityKey = new NamespacedKey(plugin, "core_entity");
        islandIndexKey = new NamespacedKey(plugin, "core_island_index");
        coreMenu = new CoreMenu(
                plugin,
                messages,
                this,
                new File(plugin.getDataFolder(), "core/core_menu.yml"));
        coreUpgradesMenu = new CoreUpgradesMenu(
                plugin,
                messages,
                this,
                new File(plugin.getDataFolder(), "core/core_upgrades.yml"));
        astralTreeMenu = new AstralTreeMenu(
                plugin,
                messages,
                this,
                new File(plugin.getDataFolder(), "core/astral_tree.yml"));
    }

    public void reloadConfiguration() {
        YamlConfiguration loaded = loadConfiguration(false);
        CoreItem.create(loaded, messages, coreItemKey, 1);
        applyConfiguration(loaded);
        coreMenu.reload();
        coreUpgradesMenu.reload();
        astralTreeMenu.reload();
    }

    private YamlConfiguration loadConfiguration(boolean saveDefaults) {
        YamlConfiguration loaded = ConfigFiles.load(configFile);
        InputStream defaultsStream = plugin.getResource("core/core.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/core.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/core.yml defaults.", exception);
        }
        return loaded;
    }

    private void applyConfiguration(YamlConfiguration loaded) {
        Material loadedDisplayMaterial = configuredBlock(loaded, "display.material", "CRYING_OBSIDIAN");
        float loadedDisplayScale = positiveFloat(loaded, "display.scale", 1.3f);
        float loadedInteractionWidth = positiveFloat(loaded, "interaction.width", 1.3f);
        float loadedInteractionHeight = positiveFloat(loaded, "interaction.height", 1.3f);
        double loadedDisplayHeightOffset = loaded.getDouble("display.height-offset", 1.8);
        double loadedTextOffset = loaded.getDouble("text-display.offset-y", 1.1);
        int loadedTextLineWidth = loaded.getInt("text-display.line-width", 200);
        int loadedRotationSpeedTicks = loaded.getInt("display.rotation-speed-ticks", 20);
        long loadedComboWindowMillis = loaded.getLong("combo.window-ms", 3000L);
        if (loadedTextLineWidth <= 0 || loadedRotationSpeedTicks <= 0 || loadedComboWindowMillis <= 0) {
            throw new IllegalArgumentException(
                    "core.yml display rotation speed, combo window, and text line width must be positive.");
        }
        String soundKey = loaded.getString("sound.key", "minecraft:block.note_block.pling");
        NamespacedKey parsedSoundKey = soundKey == null ? null : NamespacedKey.fromString(soundKey);
        Sound loadedHitSound = parsedSoundKey == null ? null : Registry.SOUNDS.get(parsedSoundKey);
        if (loadedHitSound == null) {
            throw new IllegalArgumentException("core.yml sound.key must be a valid registered sound.");
        }
        double configuredVolume = loaded.getDouble("sound.volume", 1.0);
        if (!Double.isFinite(configuredVolume) || configuredVolume < 0 || configuredVolume > Float.MAX_VALUE) {
            throw new IllegalArgumentException("core.yml sound.volume must be a non-negative finite number.");
        }
        config = loaded;
        displayMaterial = loadedDisplayMaterial;
        displayScale = loadedDisplayScale;
        interactionWidth = loadedInteractionWidth;
        interactionHeight = loadedInteractionHeight;
        displayHeightOffset = loadedDisplayHeightOffset;
        textOffset = loadedTextOffset;
        textLineWidth = loadedTextLineWidth;
        rotationSpeedTicks = loadedRotationSpeedTicks;
        comboWindowMillis = loadedComboWindowMillis;
        hitSound = loadedHitSound;
        hitSoundVolume = (float) configuredVolume;
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

    public CoreMenu menuListener() {
        return coreMenu;
    }

    public CoreUpgradesMenu upgradesMenuListener() {
        return coreUpgradesMenu;
    }

    public void attachPylonService(PylonService pylonService) {
        this.pylonService = pylonService;
        coreMenu.attachPylonService(pylonService);
    }

    public void attachCondenserService(CondenserService condenserService) {
        this.condenserService = condenserService;
        coreMenu.attachCondenserService(condenserService);
    }

    public void attachCropGeneratorService(CropGeneratorService cropGeneratorService) {
        this.cropGeneratorService = cropGeneratorService;
        coreMenu.attachCropGeneratorService(cropGeneratorService);
    }

    public void openCoreMenu(Player player, org.bukkit.entity.Entity entity) {
        Long islandIndex = entity.getPersistentDataContainer().get(islandIndexKey, PersistentDataType.LONG);
        if (islandIndex == null) {
            return;
        }
        openCoreMenu(player, islandIndex);
    }

    public void openCoreMenu(Player player, long islandIndex) {
        CoreRecord core = coreForPlayer(player, islandIndex);
        if (core != null) {
            coreMenu.open(player, core);
        }
    }

    public void openCoreUpgrades(Player player, long islandIndex) {
        coreUpgradesMenu.open(player, islandIndex);
    }

    public void openAstralTree(Player player, long islandIndex) {
        CoreRecord core = coreForPlayer(player, islandIndex);
        if (core != null) {
            astralTreeMenu.open(player, core);
        }
    }

    public AstralTreeMenu astralTreeMenuListener() {
        return astralTreeMenu;
    }

    public boolean upgradeAstralSkill(Player player, long islandIndex, AstralSkill skill) {
        CoreRecord current = coreForPlayer(player, islandIndex);
        if (current == null) {
            return false;
        }
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "astral-tree-owner-only");
            return false;
        }
        int nextLevel = current.astralSkillLevel(skill) + 1;
        if (nextLevel > skill.maxLevel()) {
            messages.send(player, "astral-skill-max-level");
            return false;
        }
        int requiredFracture = skill.fractureRequirement(nextLevel);
        if (current.prestigeCount() < requiredFracture) {
            messages.send(player, "astral-skill-fracture-required", Map.of(
                    "required", Integer.toString(requiredFracture)));
            return false;
        }
        int cost = skill.fragmentCost(nextLevel);
        if (current.coreFragments() < cost) {
            messages.send(player, "astral-skill-fragments-required", Map.of(
                    "cost", Integer.toString(cost)));
            return false;
        }
        CoreRecord upgraded = repository.upgradeAstralSkill(islandIndex, skill);
        if (upgraded == null) {
            messages.send(player, "astral-skill-upgrade-failed");
            return false;
        }
        messages.send(player, "astral-skill-upgraded", Map.of(
                "level", Integer.toString(upgraded.astralSkillLevel(skill)),
                "fragments", Integer.toString(upgraded.coreFragments())));
        return true;
    }

    public CoreRecord getCoreForMenu(Player player, long islandIndex) {
        return coreForPlayer(player, islandIndex);
    }

    public boolean upgradeEnhancement(
            Player player,
            long islandIndex,
            CoreEnhancement enhancement,
            double cost,
            int maxLevel) {
        CoreRecord current = coreForPlayer(player, islandIndex);
        if (current == null) {
            return false;
        }
        if (current.enhancementLevel(enhancement) >= maxLevel) {
            messages.send(player, "core-enhancement-max-level");
            return false;
        }
        CoreRecord upgraded = repository.upgradeEnhancement(islandIndex, enhancement, cost, maxLevel);
        if (upgraded == null) {
            messages.send(player, "core-enhancement-insufficient-energy");
            return false;
        }
        updateCoreVisuals(upgraded, System.currentTimeMillis());
        if (upgraded.hits() > 0) {
            org.bukkit.World world = plugin.getServer().getWorld(upgraded.worldId());
            org.bukkit.entity.Entity entity = world == null ? null : world.getEntity(upgraded.interactionId());
            if (entity != null) {
                scheduleComboExpiration(entity, upgraded);
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f);
        messages.send(player, "core-enhancement-upgraded", Map.of(
                "level", Integer.toString(upgraded.enhancementLevel(enhancement)),
                "cost", formatEnergy(cost)));
        return true;
    }

    public boolean fractureCore(Player player, long islandIndex) {
        CoreRecord current = coreForPlayer(player, islandIndex);
        if (current == null) {
            return false;
        }
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-fracture-owner-only");
            return false;
        }
        if (current.level() < 10) {
            messages.send(player, "core-fracture-level-required");
            return false;
        }
        if (pylonService != null && !pylonService.destroyPylons(player, islandIndex)) {
            return false;
        }
        if (condenserService != null && !condenserService.destroyForFracture(player, islandIndex)) {
            return false;
        }
        if (cropGeneratorService != null && !cropGeneratorService.destroyForFracture(player, islandIndex)) {
            return false;
        }
        CoreRecord fractured = repository.fractureCore(islandIndex);
        if (fractured == null) {
            messages.send(player, "core-fracture-level-required");
            return false;
        }
        anomalies.remove(islandIndex);
        updateCoreVisuals(fractured, System.currentTimeMillis());
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        messages.send(player, "core-fractured", Map.of(
                "prestige", Integer.toString(fractured.prestigeCount()),
                "fragments", Integer.toString(fractured.coreFragments()),
                "bonus", formatEnergy((fractured.prestigeMultiplier() - 1.0) * 100.0)));
        return true;
    }

    public double energyMultiplier(long islandIndex, long now) {
        AnomalyState state = anomalies.get(islandIndex);
        return state != null && state.hyperdriveUntil > now ? 3.0 : 1.0;
    }

    public boolean upgradeCore(Player player, long islandIndex) {
        CoreRecord current = coreForPlayer(player, islandIndex);
        if (current == null) {
            return false;
        }
        double cost = current.upgradeCost();
        if (current.energy() < cost) {
            messages.send(player, "core-upgrade-insufficient");
            return true;
        }
        CoreRecord upgraded = repository.upgradeCore(islandIndex, cost);
        if (upgraded == null) {
            messages.send(player, "core-upgrade-insufficient");
            return true;
        }
        updateCoreVisuals(upgraded, System.currentTimeMillis());
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
        messages.send(player, "core-upgrade-success", Map.of(
                "level", Integer.toString(upgraded.level()),
                "cost", formatEnergy(cost)));
        return true;
    }

    private CoreRecord coreForPlayer(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "core-menu-access-denied");
            return null;
        }
        CoreRecord core = repository.core(islandIndex);
        if (core == null) {
            messages.send(player, "core-already-placed");
        }
        return core;
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
                config,
                messages,
                coreItemKey,
                removed.level(),
                removed.energy(),
                removed.hits(),
                removed.lastHitAt(),
                removed.upgradeOvercharge(),
                removed.upgradeWindow(),
                removed.upgradeCritical(),
                removed.prestigeCount(),
                removed.coreFragments(),
                removed.astralResonance(),
                removed.astralPylonAttunement(),
                removed.upgradeCapacity());
        try {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(coreItem);
            for (ItemStack leftover : overflow.values()) {
                Location dropLocation = clickedEntity.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
                Item dropped = clickedEntity.getWorld().dropItem(dropLocation, leftover);
                dropped.setPersistent(true);
            }
            removeCoreEntities(removed);
            stopQuantumAnomaly(islandIndex);
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
                    progress.lastHitAt(),
                    progress.upgradeOvercharge(),
                    progress.upgradeWindow(),
                    progress.upgradeCritical(),
                    progress.prestigeCount(),
                    progress.coreFragments(),
                    progress.astralResonance(),
                    progress.astralPylonAttunement(),
                    progress.upgradeCapacity()));
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
        startQuantumAnomaly(itemDisplay, island.index());
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
        CoreRecord previous = repository.core(islandIndex);
        long activeComboWindow = previous == null ? comboWindowMillis : previous.comboWindowMillis(comboWindowMillis);
        double eventMultiplier = energyMultiplier(islandIndex, now);
        CoreRecord.HitResult hitResult = repository.registerHitResult(
                islandIndex, now, activeComboWindow, eventMultiplier);
        if (hitResult == null) {
            return;
        }
        CoreRecord updated = hitResult.core();
        double gainedEnergy = previous == null ? 0.0 : updated.energy() - previous.energy();
        float pitch = 1.0f + Math.min(updated.hits(), updated.maxComboHits())
                / (float) updated.maxComboHits();
        entity.getWorld().playSound(entity.getLocation(), hitSound, hitSoundVolume, pitch);
        entity.getWorld().spawnParticle(
                Particle.WAX_OFF, entity.getLocation().add(0, 0.65, 0), 8, 0.2, 0.2, 0.2, 0.05);
        if (updated.hits() >= updated.maxComboHits()) {
            entity.getWorld().spawnParticle(
                    Particle.ELECTRIC_SPARK, entity.getLocation().add(0, 0.65, 0), 24, 0.35, 0.35, 0.35, 0.1);
        }
        String anomalyFeedback = recordAnomalyHit(islandIndex, now);
        sendHitActionBar(player, updated, gainedEnergy, now, hitResult.critical(), anomalyFeedback);
        updateCoreVisuals(updated, now);
        scheduleComboExpiration(entity, updated);
    }

    private void scheduleComboExpiration(org.bukkit.entity.Entity entity, CoreRecord core) {
        long activeWindow = core.comboWindowMillis(comboWindowMillis);
        long elapsed = Math.max(0L, System.currentTimeMillis() - core.lastHitAt());
        long remaining = Math.max(1L, activeWindow - elapsed);
        long delayTicks = Math.max(1L, (remaining + 49L) / 50L);
        entity.getScheduler().runDelayed(plugin, task -> {
            CoreRecord current = repository.core(core.islandIndex());
            if (current == null || current.lastHitAt() != core.lastHitAt() || current.hits() == 0) {
                return;
            }
            long currentRemaining = current.comboWindowMillis(comboWindowMillis)
                    - Math.max(0L, System.currentTimeMillis() - current.lastHitAt());
            if (currentRemaining > 0) {
                scheduleComboExpiration(entity, current);
                return;
            }
            CoreRecord expired = repository.expireCombo(core.islandIndex(), core.lastHitAt());
            if (expired != null) {
                updateCoreVisuals(expired, System.currentTimeMillis());
            }
        }, null, delayTicks);
    }

    private void startQuantumAnomaly(ItemDisplay display, long islandIndex) {
        if (anomalyTasks.containsKey(islandIndex)) {
            return;
        }
        io.papermc.paper.threadedregions.scheduler.ScheduledTask[] taskReference =
                new io.papermc.paper.threadedregions.scheduler.ScheduledTask[1];
        io.papermc.paper.threadedregions.scheduler.ScheduledTask scheduled =
                display.getScheduler().runAtFixedRate(plugin, task -> {
                    if (!display.isValid() || repository.core(islandIndex) == null) {
                        task.cancel();
                        anomalyTasks.remove(islandIndex, taskReference[0]);
                        if (repository.core(islandIndex) == null) {
                            anomalies.remove(islandIndex);
                        }
                        return;
                    }
                    tickQuantumAnomaly(display, islandIndex);
                }, () -> {
                    anomalyTasks.remove(islandIndex, taskReference[0]);
                }, 1L, 20L);
        if (scheduled != null) {
            taskReference[0] = scheduled;
            anomalyTasks.put(islandIndex, scheduled);
        }
    }

    private void stopQuantumAnomaly(long islandIndex) {
        io.papermc.paper.threadedregions.scheduler.ScheduledTask task = anomalyTasks.remove(islandIndex);
        if (task != null) {
            task.cancel();
        }
        anomalies.remove(islandIndex);
    }

    private void tickQuantumAnomaly(ItemDisplay display, long islandIndex) {
        long now = System.currentTimeMillis();
        AnomalyState state = anomalies.computeIfAbsent(islandIndex, ignored -> new AnomalyState());
        CoreRecord core = repository.core(islandIndex);
        if (core == null) {
            anomalies.remove(islandIndex, state);
            return;
        }
        if (state.unstableUntil > now) {
            org.bukkit.World world = display.getWorld();
            for (int point = 0; point < 12; point++) {
                double angle = 2.0 * Math.PI * point / 12;
                Location particle = new Location(
                        world,
                        core.x() + Math.cos(angle) * 1.1,
                        core.y() + 0.6 + Math.sin(angle * 2) * 0.25,
                        core.z() + Math.sin(angle) * 1.1);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, particle, 2, 0.05, 0.05, 0.05, 0.01);
            }
            return;
        }
        if (state.unstableUntil != 0) {
            state.unstableUntil = 0;
            state.hits = 0;
            state.targetHits = 0;
            state.nextAnomalyAllowedAt = now + 10 * 60 * 1000L;
        }
        if (state.hyperdriveUntil != 0 && state.hyperdriveUntil <= now) {
            state.hyperdriveUntil = 0;
            state.nextAnomalyAllowedAt = now + 10 * 60 * 1000L;
        }
        if (now >= state.nextAnomalyAllowedAt
                && state.hyperdriveUntil <= now
                && ThreadLocalRandom.current().nextDouble() < 0.002) {
            state.unstableUntil = now + 20_000L;
            state.hits = 0;
            state.targetHits = Math.max(
                    15,
                    countNearbyBuilders(display, core, islandService.islandAt(
                            new Location(display.getWorld(), core.x(), core.y(), core.z())).orElse(null)) * 12);
            notifyQuantumAnomaly(islandIndex, state.targetHits);
        }
    }

    private int countNearbyBuilders(ItemDisplay display, CoreRecord core, IslandData island) {
        if (island == null) {
            return 0;
        }
        Location center = new Location(display.getWorld(), core.x(), core.y(), core.z());
        return (int) display.getWorld().getNearbyEntities(center, 16, 16, 16).stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .filter(player -> island.canBuild(player.getUniqueId()))
                .filter(player -> player.getLocation().distanceSquared(center) <= 256.0)
                .count();
    }

    private void notifyQuantumAnomaly(long islandIndex, int targetHits) {
        String message = "&b&l¡ANOMALÍA DETECTADA! &7Golpea " + targetHits + " veces rápido (0/" + targetHits + ")";
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, task -> {
                IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
                if (island != null && island.index() == islandIndex) {
                    player.sendActionBar(messages.parseComponent(message));
                }
            }, null);
        }
    }

    private String recordAnomalyHit(long islandIndex, long now) {
        AnomalyState state = anomalies.get(islandIndex);
        if (state == null) {
            return "";
        }
        if (state.unstableUntil > now) {
            state.hits++;
            if (state.hits >= state.targetHits) {
                state.unstableUntil = 0;
                state.hits = 0;
                state.hyperdriveUntil = now + 30_000L;
                CoreRecord core = repository.core(islandIndex);
                org.bukkit.World world = core == null ? null : plugin.getServer().getWorld(core.worldId());
                if (core != null && world != null) {
                    Location location = new Location(world, core.x(), core.y(), core.z());
                    world.playSound(location, Sound.ENTITY_WITHER_SPAWN, 1.0f, 2.0f);
                }
                return "&d&lHIPERIMPULSO ACTIVADO &7x3.0 por 30s";
            }
            return "&b&l¡ANOMALÍA DETECTADA! &7Golpea rápido (" + state.hits + "/" + state.targetHits + ")";
        }
        if (state.hyperdriveUntil > now) {
            return "&d&lHIPERIMPULSO &7x3.0";
        }
        return "";
    }

    public CoreRecord addPassiveEnergy(long islandIndex, double yieldRatio) {
        CoreRecord core = repository.core(islandIndex);
        if (core == null) {
            return null;
        }
        double amount = core.baseEnergy() * yieldRatio * core.astralPylonMultiplier();
        CoreRecord updated = repository.addPassiveEnergy(islandIndex, amount);
        if (updated != null) {
            updateCoreVisuals(updated, System.currentTimeMillis());
        }
        return updated;
    }

    public void refreshCore(CoreRecord core, long now) {
        updateCoreVisuals(core, now);
    }

    private void sendHitActionBar(
            Player player, CoreRecord core, double gainedEnergy, long now, boolean critical, String anomalyFeedback) {
        int filledSegments = (int) Math.round(Math.min(core.hits(), core.maxComboHits())
                / (double) core.maxComboHits() * 10);
        String bar = "&a" + "|".repeat(filledSegments)
                + "&8" + ".".repeat(10 - filledSegments);
        String energyFeedback = core.energy() >= core.maxEnergy()
                ? "&cTanque lleno &7(" + formatEnergy(core.energy()) + "/" +
                        formatEnergy(core.maxEnergy()) + "⚡)"
                : "&6+" + formatEnergy(gainedEnergy) + "⚡";
        String actionBar = (critical ? "&c¡CRÍTICO x3! &r" : "")
                + (anomalyFeedback.isEmpty() ? "" : anomalyFeedback + " &8| ")
                + energyFeedback
                + " &8| &bCombo x" + String.format(
                        Locale.ROOT, "%.2f", core.combo(now, core.comboWindowMillis(comboWindowMillis)))
                + " &7[" + bar + "&7]";
        player.sendActionBar(messages.parseComponent(actionBar));
    }

    public void onEntityLoad(org.bukkit.entity.Entity entity) {
        Long islandIndex = entity.getPersistentDataContainer().get(islandIndexKey, PersistentDataType.LONG);
        if (islandIndex == null || !repository.hasCore(islandIndex)) {
            return;
        }
        if (entity instanceof ItemDisplay itemDisplay) {
            startRotation(itemDisplay);
            startQuantumAnomaly(itemDisplay, islandIndex);
        } else if (entity instanceof TextDisplay) {
            updateCoreVisuals(repository.core(islandIndex), System.currentTimeMillis());
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

    private void updateCoreVisuals(CoreRecord core, long now) {
        org.bukkit.World world = plugin.getServer().getWorld(core.worldId());
        if (world == null) {
            return;
        }
        Location coreLocation = new Location(world, core.x(), core.y(), core.z());
        plugin.getServer().getRegionScheduler().run(plugin, coreLocation, task -> {
            long activeComboWindow = core.comboWindowMillis(comboWindowMillis);
            int activeHits = core.lastHitAt() != 0 && now - core.lastHitAt() <= activeComboWindow
                    ? Math.min(core.hits(), core.maxComboHits())
                    : 0;
            float comboScale = displayScale * (1.0f + (activeHits / (float) core.maxComboHits()) * 0.35f);
            org.bukkit.entity.Entity itemEntity = world.getEntity(core.itemDisplayId());
            if (itemEntity instanceof ItemDisplay itemDisplay) {
                startRotation(itemDisplay);
                setDisplayScale(itemDisplay, comboScale, activeHits == 0 ? 5 : 2);
            }
            org.bukkit.entity.Entity textEntity = world.getEntity(core.textDisplayId());
            if (textEntity instanceof TextDisplay textDisplay) {
                textDisplay.teleport(coreLocation.clone().add(0, textOffset + (comboScale - displayScale), 0));
                updateHologram(textDisplay, core, now);
            }
        });
    }

    private void setDisplayScale(ItemDisplay display, float scale, int transitionTicks) {
        display.getScheduler().run(plugin, task -> {
            DisplayAnimation animation = displayAnimations.computeIfAbsent(
                    display.getUniqueId(), ignored -> new DisplayAnimation(display.getTransformation().getScale().x));
            animation.targetScale = scale;
            animation.scaleTransitionTicks = transitionTicks;
        }, null);
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
                        .replace("%combo%", String.format(
                                Locale.ROOT,
                                "%.2f",
                                core.combo(now, core.comboWindowMillis(comboWindowMillis))))
                        .replace("%hits%", Integer.toString(
                                now - core.lastHitAt() > core.comboWindowMillis(comboWindowMillis)
                                        ? 0
                                        : core.hits()));
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
        UUID displayId = display.getUniqueId();
        if (!rotatingDisplays.add(displayId)) {
            return;
        }
        display.getScheduler().run(plugin, task -> {
            Transformation transformation = display.getTransformation();
            displayAnimations.putIfAbsent(displayId, new DisplayAnimation(transformation.getScale().x));
            display.getScheduler().runAtFixedRate(plugin, animationTask -> {
                if (!display.isValid()) {
                    animationTask.cancel();
                    rotatingDisplays.remove(displayId);
                    displayAnimations.remove(displayId);
                    return;
                }
                DisplayAnimation state = displayAnimations.get(displayId);
                if (state == null) {
                    animationTask.cancel();
                    rotatingDisplays.remove(displayId);
                    return;
                }
                if (state.scaleTransitionTicks > 0) {
                    state.currentScale += (state.targetScale - state.currentScale) / state.scaleTransitionTicks;
                    state.scaleTransitionTicks--;
                } else {
                    state.currentScale = state.targetScale;
                }
                state.yaw += (float) (Math.PI / (2.0 * rotationSpeedTicks));
                Transformation current = display.getTransformation();
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(1);
                display.setTransformation(new Transformation(
                        new Vector3f(current.getTranslation()),
                        new Quaternionf(current.getLeftRotation()),
                        new Vector3f(state.currentScale, state.currentScale, state.currentScale),
                        new Quaternionf().rotateY(state.yaw)));
            }, () -> {
                rotatingDisplays.remove(displayId);
                displayAnimations.remove(displayId);
            }, 1L, 1L);
        }, () -> {
            rotatingDisplays.remove(displayId);
            displayAnimations.remove(displayId);
        });
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

    private Material configuredBlock(YamlConfiguration config, String path, String fallback) {
        Material material = Material.matchMaterial(config.getString(path, fallback));
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("core.yml " + path + " must be a valid block material.");
        }
        return material;
    }

    private float positiveFloat(YamlConfiguration config, String path, float fallback) {
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
