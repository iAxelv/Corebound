package com.corebound.thecore.incremental;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.config.ConfigFiles;
import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CropGeneratorService implements Listener {
    private static final long RANGE_GUIDE_PERIOD_TICKS = 12L;
    private static final long IDLE_RETRY_INTERVAL_TICKS = 200L;
    private static final int MAX_LEVEL = 10;
    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA,
            Material.SWEET_BERRY_BUSH,
            Material.PUMPKIN_STEM,
            Material.MELON_STEM,
            Material.ATTACHED_PUMPKIN_STEM,
            Material.ATTACHED_MELON_STEM,
            Material.TORCHFLOWER_CROP,
            Material.PITCHER_CROP);

    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final CoreService coreService;
    private final CoreRepository coreRepository;
    private final CropGeneratorRepository repository;
    private final PylonService pylons;
    private final CondenserService condenserService;
    private final Messages messages;
    private final File configFile;
    private final NamespacedKey itemKey;
    private final NamespacedKey itemIslandKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey itemLevelKey;
    private final NamespacedKey itemStateKey;
    private final NamespacedKey itemRuneSlotsKey;
    private final List<NamespacedKey> itemRuneTypeKeys;
    private final List<NamespacedKey> itemRuneTierKeys;
    private final NamespacedKey entityKey;
    private final NamespacedKey entityIdKey;
    private final Map<UUID, ScheduledTask> visualTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGrowthFeedback = new ConcurrentHashMap<>();
    private final Set<UUID> rangeGuidePlayers = ConcurrentHashMap.newKeySet();
    private final CropGeneratorMenu menu;
    private final CropGeneratorRuneMenu runeMenu;
    private YamlConfiguration config;
    private Material itemMaterial;
    private double displayScale;
    private double displayHeightOffset;
    private int rotationPeriodTicks;
    private double purchaseCost;
    private double upgradeBaseCost;
    private double upgradeCostMultiplier;
    private double baseEnergyCost;
    private double energyCostPerLevel;
    private double baseGrowthStages;
    private double growthStagesPerLevel;
    private double maximumHarvestBonus;
    private long energyIntervalTicks;
    private int pickupLockCoreLevel;

    public CropGeneratorService(
            TheCorePlugin plugin,
            IslandService islandService,
            CoreService coreService,
            CoreRepository coreRepository,
            CropGeneratorRepository repository,
            PylonService pylons,
            CondenserService condenserService,
            Messages messages,
            File configFile) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.coreService = coreService;
        this.coreRepository = coreRepository;
        this.repository = repository;
        this.pylons = pylons;
        this.condenserService = condenserService;
        this.messages = messages;
        this.configFile = configFile;
        itemKey = new NamespacedKey(plugin, "crop_generator_item");
        itemIslandKey = new NamespacedKey(plugin, "crop_generator_item_island");
        itemIdKey = new NamespacedKey(plugin, "crop_generator_item_id");
        itemLevelKey = new NamespacedKey(plugin, "crop_generator_item_level");
        itemStateKey = new NamespacedKey(plugin, "crop_generator_item_state");
        itemRuneSlotsKey = new NamespacedKey(plugin, "crop_generator_rune_slots");
        itemRuneTypeKeys = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> new NamespacedKey(plugin, "crop_generator_rune_" + index + "_type"))
                .toList();
        itemRuneTierKeys = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> new NamespacedKey(plugin, "crop_generator_rune_" + index + "_tier"))
                .toList();
        entityKey = new NamespacedKey(plugin, "crop_generator_entity");
        entityIdKey = new NamespacedKey(plugin, "crop_generator_id");
        applyConfiguration(loadConfiguration(true));
        menu = new CropGeneratorMenu(messages, this);
        runeMenu = new CropGeneratorRuneMenu(messages, this, pylons);
    }

    public void reloadConfiguration() {
        applyConfiguration(loadConfiguration(false));
        repository.all().forEach(generator -> {
            ScheduledTask visual = visualTasks.remove(generator.generatorId());
            if (visual != null) {
                visual.cancel();
            }
            refreshVisuals(generator);
        });
    }

    private YamlConfiguration loadConfiguration(boolean saveDefaults) {
        YamlConfiguration loaded = ConfigFiles.load(configFile);
        InputStream defaultsStream = plugin.getResource("core/crop_generator.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/crop_generator.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/crop_generator.yml defaults.", exception);
        }
        return loaded;
    }

    private void applyConfiguration(YamlConfiguration loaded) {
        Material loadedItemMaterial = Material.matchMaterial(
                loaded.getString("crop-generator.item.material", "COMPOSTER"));
        double loadedScale = loaded.getDouble("crop-generator.display.scale", 1.1);
        double loadedHeightOffset = loaded.getDouble("crop-generator.display.height-offset", 1.8);
        int loadedRotationPeriodTicks = loaded.getInt("crop-generator.display.rotation-period-ticks", 40);
        double loadedPurchaseCost = loaded.getDouble("crop-generator.purchase-cost", 750.0);
        double loadedUpgradeBaseCost = loaded.getDouble("crop-generator.upgrade.base-cost", 400.0);
        double loadedUpgradeMultiplier = loaded.getDouble("crop-generator.upgrade.cost-multiplier", 1.7);
        double loadedBaseEnergyCost = loaded.getDouble("crop-generator.energy.base-cost", 5.0);
        double loadedEnergyCostPerLevel = loaded.getDouble("crop-generator.energy.cost-per-level", 2.0);
        long loadedEnergyIntervalTicks = loaded.getLong("crop-generator.energy.interval-ticks", 1200L);
        double loadedBaseGrowthStages = loaded.getDouble("crop-generator.growth.base-extra-stages", 0.1);
        double loadedGrowthStagesPerLevel =
                loaded.getDouble("crop-generator.growth.extra-stages-per-level", 0.2);
        double loadedMaximumHarvestBonus = loaded.getDouble("crop-generator.runes.maximum-harvest-bonus", 1.0);
        int loadedPickupLockCoreLevel = loaded.getInt("crop-generator.pickup-lock-core-level", 10);
        if (loadedItemMaterial == null || !loadedItemMaterial.isItem()
                || !Double.isFinite(loadedScale) || loadedScale <= 0 || loadedScale > Float.MAX_VALUE
                || !Double.isFinite(loadedHeightOffset) || loadedHeightOffset <= 0
                || loadedRotationPeriodTicks < 10 || loadedRotationPeriodTicks > 200
                || !positiveFinite(loadedPurchaseCost)
                || !positiveFinite(loadedUpgradeBaseCost)
                || !Double.isFinite(loadedUpgradeMultiplier) || loadedUpgradeMultiplier <= 1.0
                || !Double.isFinite(loadedBaseEnergyCost) || loadedBaseEnergyCost < 0
                || !Double.isFinite(loadedEnergyCostPerLevel) || loadedEnergyCostPerLevel < 0
                || loadedEnergyIntervalTicks < 20 || loadedEnergyIntervalTicks > 1_728_000L
                || !Double.isFinite(loadedBaseGrowthStages) || loadedBaseGrowthStages < 0
                || !Double.isFinite(loadedGrowthStagesPerLevel) || loadedGrowthStagesPerLevel < 0
                || !Double.isFinite(loadedMaximumHarvestBonus) || loadedMaximumHarvestBonus < 0
                || loadedPickupLockCoreLevel < minimumCoreLevel()) {
            throw new IllegalArgumentException("crop_generator.yml contains invalid material, progression, or costs.");
        }
        int secondRuneSlotLevel = loaded.getInt("crop-generator.runes.slot-required-level.2", 3);
        int thirdRuneSlotLevel = loaded.getInt("crop-generator.runes.slot-required-level.3", 6);
        if (secondRuneSlotLevel < 1 || thirdRuneSlotLevel <= secondRuneSlotLevel) {
            throw new IllegalArgumentException(
                    "crop_generator.yml rune slot requirements must increase with the slot count.");
        }
        validateMenuConfiguration(loaded);
        config = loaded;
        itemMaterial = loadedItemMaterial;
        displayScale = loadedScale;
        displayHeightOffset = loadedHeightOffset;
        rotationPeriodTicks = loadedRotationPeriodTicks;
        purchaseCost = loadedPurchaseCost;
        upgradeBaseCost = loadedUpgradeBaseCost;
        upgradeCostMultiplier = loadedUpgradeMultiplier;
        baseEnergyCost = loadedBaseEnergyCost;
        energyCostPerLevel = loadedEnergyCostPerLevel;
        energyIntervalTicks = loadedEnergyIntervalTicks;
        baseGrowthStages = loadedBaseGrowthStages;
        growthStagesPerLevel = loadedGrowthStagesPerLevel;
        maximumHarvestBonus = loadedMaximumHarvestBonus;
        pickupLockCoreLevel = loadedPickupLockCoreLevel;
        for (CropGeneratorRecord generator : repository.all()) {
            int unlockedSlots = maximumRuneSlotsForLevel(generator.level());
            if (generator.runeSlots() != unlockedSlots) {
                repository.update(new CropGeneratorRecord(
                        generator.islandIndex(), generator.generatorId(), generator.worldId(),
                        generator.x(), generator.y(), generator.z(), generator.itemDisplayId(),
                        generator.interactionId(), generator.textDisplayId(), generator.level(),
                        generator.state(), unlockedSlots, generator.runes()));
            }
        }

    }

    private boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private void validateMenuConfiguration(YamlConfiguration loaded) {
        List<String> itemPaths = List.of(
                "main.info.material",
                "main.state.material",
                "main.runes.material",
                "main.upgrade.material",
                "main.upgrade.max-material",
                "main.close.material",
                "runes.empty-slot.material",
                "runes.progression.locked-material",
                "runes.progression.unlocked-material",
                "runes.progression.max-material",
                "runes.back.material");
        for (String path : itemPaths) {
            Material material = Material.matchMaterial(
                    loaded.getString("crop-generator.menus." + path, ""));
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("crop_generator.yml menu material is invalid: " + path);
            }
        }

        Set<Integer> mainSlots = new java.util.HashSet<>();
        for (String name : List.of("info", "state", "runes", "upgrade", "close")) {
            int slot = loaded.getInt("crop-generator.menus.main.slots." + name, -1);
            if (slot < 0 || slot >= 45 || !mainSlots.add(slot)) {
                throw new IllegalArgumentException("crop_generator.yml main menu slots must be valid and unique.");
            }
        }

        int progressionSlot = loaded.getInt("crop-generator.menus.runes.progression-slot", 22);
        int backSlot = loaded.getInt("crop-generator.menus.runes.back-slot", 26);
        if (progressionSlot < 0 || progressionSlot >= 27 || backSlot < 0 || backSlot >= 27
                || progressionSlot == backSlot || progressionSlot == 4 || backSlot == 4) {
            throw new IllegalArgumentException("crop_generator.yml rune menu control slots are invalid.");
        }
        for (int count = 1; count <= 3; count++) {
            int expectedCount = count;
            List<Integer> runeSlots = loaded.getIntegerList(
                    "crop-generator.menus.runes.slot-layout." + count);
            if (runeSlots.size() != count
                    || new java.util.HashSet<>(runeSlots).size() != count
                    || runeSlots.stream().anyMatch(slot -> slot < 9 || slot >= 18
                            || slot == progressionSlot || slot == backSlot)) {
                throw new IllegalArgumentException(
                        "crop_generator.yml rune menu layout is invalid for " + expectedCount + " slots.");
            }
        }
    }

    public CropGeneratorMenu menuListener() {
        return menu;
    }

    public CropGeneratorRuneMenu runeMenuListener() {
        return runeMenu;
    }

    public double purchaseCost() {
        return purchaseCost;
    }

    public boolean hasGenerator(long islandIndex) {
        return repository.get(islandIndex) != null;
    }

    public int minimumCoreLevel() {
        return condenserService.minimumCoreLevel() + 2;
    }

    public int maximumLevel() {
        return MAX_LEVEL;
    }

    public boolean isGeneratorItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public boolean isGeneratorEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(entityKey, PersistentDataType.BYTE);
    }

    public boolean buy(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "crop-generator-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < minimumCoreLevel()) {
            messages.send(player, "crop-generator-core-level-required", Map.of(
                    "level", Integer.toString(minimumCoreLevel())));
            return false;
        }
        UUID itemId = repository.reserveItem(islandIndex);
        CoreRecord chargedCore = coreRepository.spendEnergy(islandIndex, purchaseCost);
        if (chargedCore == null) {
            repository.releasePendingItem(islandIndex, itemId);
            messages.send(player, "crop-generator-insufficient-energy");
            return false;
        }
        try {
            deliverItem(player, createItem(islandIndex, itemId, 0, CondenserState.ACTIVE, 1, Map.of()));
        } catch (RuntimeException exception) {
            repository.releasePendingItem(islandIndex, itemId);
            coreRepository.restoreEnergy(islandIndex, purchaseCost);
            plugin.getLogger().log(Level.SEVERE, "Could not deliver a purchased crop generator.", exception);
            messages.send(player, "crop-generator-purchase-failed");
            return false;
        }
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        messages.send(player, "crop-generator-purchased", Map.of("cost", formatEnergy(purchaseCost)));
        return true;
    }

    private void deliverItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            dropped.setPersistent(true);
        }
    }

    private ItemStack createItem(
            long islandIndex,
            UUID itemId,
            int level,
            CondenserState state,
            int runeSlots,
            Map<Integer, CellRecord> runes) {
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&aCatalizador de Cultivos"));
        meta.lore(List.of(
                messages.parseComponent("&7Nivel: &e" + level + "/" + MAX_LEVEL),
                messages.parseComponent("&7Estado: &a" + stateLabel(state)),
                messages.parseComponent("&7Acelera los cultivos de la isla."),
                messages.parseComponent("&7Solo se puede colocar uno por isla."),
                messages.parseComponent("&8Mayús+clic derecho para recogerlo.")));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(itemIslandKey, PersistentDataType.LONG, islandIndex);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, itemId.toString());
        meta.getPersistentDataContainer().set(itemLevelKey, PersistentDataType.INTEGER, level);
        meta.getPersistentDataContainer().set(itemStateKey, PersistentDataType.STRING, state.name());
        meta.getPersistentDataContainer().set(itemRuneSlotsKey, PersistentDataType.INTEGER, runeSlots);
        runes.forEach((slot, rune) -> {
            if (slot >= 0 && slot < itemRuneTypeKeys.size()) {
                meta.getPersistentDataContainer().set(
                        itemRuneTypeKeys.get(slot), PersistentDataType.STRING, rune.type().name());
                meta.getPersistentDataContainer().set(
                        itemRuneTierKeys.get(slot), PersistentDataType.INTEGER, rune.tier());
            }
        });
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }

    private Map<Integer, CellRecord> readItemRunes(ItemMeta meta, int level, int runeSlots) {
        if (runeSlots < 1 || runeSlots > maximumRuneSlotsForLevel(level)) {
            return null;
        }
        Map<Integer, CellRecord> runes = new java.util.HashMap<>();
        int maximumTier = CellRecord.maximumTierForCropGeneratorLevel(level);
        for (int slot = 0; slot < itemRuneTypeKeys.size(); slot++) {
            String type = meta.getPersistentDataContainer().get(
                    itemRuneTypeKeys.get(slot), PersistentDataType.STRING);
            Integer tier = meta.getPersistentDataContainer().get(
                    itemRuneTierKeys.get(slot), PersistentDataType.INTEGER);
            if (type == null && tier == null) {
                continue;
            }
            if (type == null || tier == null) {
                return null;
            }
            try {
                CellRecord rune = new CellRecord(CellType.valueOf(type), tier);
                if (!rune.type().isCropGeneratorRune() || rune.tier() > maximumTier) {
                    return null;
                }
                runes.put(slot, rune);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return Map.copyOf(runes);
    }

    public boolean place(Player player, Block clickedBlock) {
        if (!clickedBlock.getType().isSolid()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP).getType().isAir()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP, 2).getType().isAir()) {
            messages.send(player, "crop-generator-needs-clearance");
            return false;
        }
        IslandData island = islandService.islandAt(clickedBlock.getLocation()).orElse(null);
        if (island == null || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "crop-generator-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(island.index());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < minimumCoreLevel()) {
            messages.send(player, "crop-generator-core-level-required", Map.of(
                    "level", Integer.toString(minimumCoreLevel())));
            return false;
        }
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        ItemMeta heldMeta = heldItem.getItemMeta();
        UUID itemId = parseUuid(heldMeta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING));
        Long boundIsland = heldMeta.getPersistentDataContainer().get(itemIslandKey, PersistentDataType.LONG);
        if (boundIsland == null || boundIsland != island.index()) {
            messages.send(player, "crop-generator-item-wrong-island");
            return false;
        }
        if (!repository.canPlace(island.index(), itemId)) {
            messages.send(player, "crop-generator-item-invalidated");
            return false;
        }
        Location generatorLocation = new Location(
                clickedBlock.getWorld(),
                clickedBlock.getX() + 0.5,
                clickedBlock.getY() + displayHeightOffset,
                clickedBlock.getZ() + 0.5);
        double distance = generatorLocation.distance(new Location(
                clickedBlock.getWorld(), core.x(), core.y(), core.z()));
        if (distance < pylons.minimumDistance() || distance > pylons.maximumDistance()) {
            messages.send(player, "crop-generator-distance-invalid", Map.of(
                    "min", formatEnergy(pylons.minimumDistance()),
                    "max", formatEnergy(pylons.maximumDistance())));
            return false;
        }
        int level = heldMeta.getPersistentDataContainer().getOrDefault(
                itemLevelKey, PersistentDataType.INTEGER, 0);
        if (level < 0 || level > MAX_LEVEL) {
            messages.send(player, "crop-generator-item-invalidated");
            return false;
        }
        int runeSlots = heldMeta.getPersistentDataContainer().getOrDefault(
                itemRuneSlotsKey, PersistentDataType.INTEGER, 1);
        Map<Integer, CellRecord> runes = readItemRunes(heldMeta, level, runeSlots);
        CondenserState state = parseState(heldMeta.getPersistentDataContainer().getOrDefault(
                itemStateKey, PersistentDataType.STRING, CondenserState.ACTIVE.name()));
        if (runes == null || state == null) {
            messages.send(player, "crop-generator-item-invalidated");
            return false;
        }

        UUID generatorId = UUID.randomUUID();
        ItemDisplay itemDisplay = null;
        Interaction interaction = null;
        TextDisplay textDisplay = null;
        double placementEnergyPaid = 0.0;
        try {
            itemDisplay = clickedBlock.getWorld().spawn(generatorLocation, ItemDisplay.class, display -> {
                configureDisplay(display);
                markEntity(display, generatorId);
            });
            Location interactionLocation = generatorLocation.clone().subtract(0, 0.6, 0);
            interaction = clickedBlock.getWorld().spawn(interactionLocation, Interaction.class, entity -> {
                entity.setInteractionWidth(1.0f);
                entity.setInteractionHeight(1.5f);
                entity.setResponsive(true);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(true);
                markEntity(entity, generatorId);
            });
            Location textLocation = generatorLocation.clone().add(0, 1.1, 0);
            textDisplay = clickedBlock.getWorld().spawn(textLocation, TextDisplay.class, display -> {
                display.setBillboard(Display.Billboard.CENTER);
                display.setGravity(false);
                display.setPersistent(true);
                display.setInvulnerable(true);
                markEntity(display, generatorId);
            });
            CropGeneratorRecord generator = new CropGeneratorRecord(
                    island.index(),
                    generatorId,
                    clickedBlock.getWorld().getUID(),
                    generatorLocation.getX(),
                    generatorLocation.getY(),
                    generatorLocation.getZ(),
                    itemDisplay.getUniqueId(),
                    interaction.getUniqueId(),
                    textDisplay.getUniqueId(),
                    level,
                    state,
                    runeSlots,
                    runes);
            repository.add(generator, itemId);
            if (state == CondenserState.ACTIVE) {
                CoreRecord charged = coreRepository.spendEnergy(island.index(), energyCost(generator));
                if (charged == null) {
                    generator = generator.withState(CondenserState.IDLE);
                    repository.update(generator);
                } else {
                    placementEnergyPaid = energyCost(generator);
                    coreService.refreshCore(charged, System.currentTimeMillis());
                }
            }
            refreshVisuals(generator);
            consumeHeldItem(player, heldItem);
            startVisualAnimationFor(generatorId);
            startVisualAnimation(itemDisplay, generatorId);
            messages.send(player, "crop-generator-placed");
            return true;
        } catch (RuntimeException exception) {
            if (placementEnergyPaid > 0) {
                coreRepository.restoreEnergy(island.index(), placementEnergyPaid);
            }
            CropGeneratorRecord placed = repository.get(island.index());
            if (placed != null && placed.generatorId().equals(generatorId)) {
                repository.rollbackPlacement(island.index(), generatorId, itemId);
            }
            cancelTasks(generatorId);
            removeIfPresent(textDisplay);
            removeIfPresent(interaction);
            removeIfPresent(itemDisplay);
            plugin.getLogger().log(Level.SEVERE, "Could not place crop generator on island " + island.name(),
                    exception);
            messages.send(player, "crop-generator-place-failed");
            return false;
        }
    }

    private void consumeHeldItem(Player player, ItemStack item) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public void onPlayerJoin(Player player) {
        if (!rangeGuidePlayers.add(player.getUniqueId())) {
            return;
        }
        if (player.getScheduler().runAtFixedRate(
                        plugin,
                        task -> {
                            if (player.isOnline()
                                    && isGeneratorItem(player.getInventory().getItemInMainHand())) {
                                pylons.showPlacementRange(player);
                            }
                        },
                        () -> rangeGuidePlayers.remove(player.getUniqueId()),
                        1L,
                        RANGE_GUIDE_PERIOD_TICKS)
                == null) {
            rangeGuidePlayers.remove(player.getUniqueId());
        }
    }

    public void initializeLoadedEntities() {
        for (CropGeneratorRecord generator : repository.all()) {
            World world = plugin.getServer().getWorld(generator.worldId());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, generator.x(), generator.y(), generator.z());
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                Entity entity = world.getEntity(generator.interactionId());
                if (entity instanceof Interaction interaction) {
                    refreshVisuals(generator);
                    startVisualAnimationFor(generator.generatorId());
                }
                Entity displayEntity = world.getEntity(generator.itemDisplayId());
                if (displayEntity instanceof ItemDisplay display) {
                    configureDisplay(display);
                    startVisualAnimation(display, generator.generatorId());
                }
            });
        }
    }

    public void onEntityLoad(Entity entity) {
        if (!isGeneratorEntity(entity)) {
            return;
        }
        CropGeneratorRecord generator = generatorFrom(entity);
        if (generator == null) {
            removeIfPresent(entity);
            return;
        }
        if (entity instanceof ItemDisplay display) {
            configureDisplay(display);
            startVisualAnimation(display, generator.generatorId());
        } else if (entity instanceof Interaction interaction) {
            refreshVisuals(generator);
            startVisualAnimationFor(generator.generatorId());
        }
    }

    private CropGeneratorRecord generatorFrom(Entity entity) {
        String id = entity.getPersistentDataContainer().get(entityIdKey, PersistentDataType.STRING);
        UUID generatorId = parseUuid(id);
        return generatorId == null ? null : repository.getById(generatorId);
    }

    private void markEntity(Entity entity, UUID generatorId) {
        entity.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(entityIdKey, PersistentDataType.STRING, generatorId.toString());
    }

    private void startVisualAnimationFor(UUID generatorId) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator == null) {
            return;
        }
        World world = plugin.getServer().getWorld(generator.worldId());
        Entity displayEntity = world == null ? null : world.getEntity(generator.itemDisplayId());
        if (displayEntity instanceof ItemDisplay display) {
            startVisualAnimation(display, generatorId);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        if (!CROPS.contains(block.getType())) {
            return;
        }
        CropGeneratorRecord generator = activeGeneratorAt(block.getLocation());
        if (generator == null) {
            return;
        }
        if (!(event.getNewState().getBlockData() instanceof Ageable crop)) {
            return;
        }
        int extraStages = CropGeneratorMath.additionalGrowthStages(
                generator.level(), baseGrowthStages, growthStagesPerLevel, ThreadLocalRandom.current().nextDouble());
        if (extraStages <= 0) {
            return;
        }
        crop.setAge((int) Math.min(
                crop.getMaximumAge(), (long) crop.getAge() + extraStages));
        event.getNewState().setBlockData(crop);
        showGrowthFeedback(generator, block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropHarvest(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (!CROPS.contains(block.getType())
                || !(event.getBlockState().getBlockData() instanceof Ageable crop)
                || crop.getAge() < crop.getMaximumAge()) {
            return;
        }
        CropGeneratorRecord generator = activeGeneratorAt(block.getLocation());
        if (generator == null) {
            return;
        }
        double bonusRatio = harvestBonus(generator);
        if (bonusRatio <= 0) {
            return;
        }
        for (Item droppedItem : event.getItems()) {
            ItemStack stack = droppedItem.getItemStack();
            int additional = CropGeneratorMath.additionalHarvestItems(
                    stack.getAmount(), bonusRatio, ThreadLocalRandom.current().nextDouble());
            if (additional > 0) {
                stack.setAmount(Math.min(stack.getMaxStackSize(), stack.getAmount() + additional));
                droppedItem.setItemStack(stack);
            }
        }
    }

    private CropGeneratorRecord activeGeneratorAt(Location location) {
        IslandData island = islandService.islandAt(location).orElse(null);
        if (island == null) {
            return null;
        }
        CropGeneratorRecord generator = repository.get(island.index());
        return generator != null && generator.state() == CondenserState.ACTIVE ? generator : null;
    }

    private void showGrowthFeedback(CropGeneratorRecord generator, Location cropLocation) {
        long now = System.nanoTime();
        Long previous = lastGrowthFeedback.putIfAbsent(generator.generatorId(), now);
        if (previous != null) {
            if (now - previous < 500_000_000L
                    || !lastGrowthFeedback.replace(generator.generatorId(), previous, now)) {
                return;
            }
        }
        cropLocation.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER, cropLocation.clone().add(0.5, 0.75, 0.5), 3, 0.2, 0.2, 0.2, 0.01);
    }

    public boolean openMenu(Player player, Entity entity) {
        CropGeneratorRecord generator = generatorFrom(entity);
        if (generator == null || !canManage(player, generator)) {
            return false;
        }
        menu.open(player, generator);
        return true;
    }

    public void openMenu(Player player, UUID generatorId) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator != null && canManage(player, generator)) {
            menu.open(player, generator);
        }
    }

    public void openRuneMenu(Player player, UUID generatorId) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator != null && canManage(player, generator)) {
            runeMenu.open(player, generator);
        }
    }

    public boolean pickup(Player player, Entity entity) {
        CropGeneratorRecord generator = generatorFrom(entity);
        if (generator == null) {
            return false;
        }
        IslandData island = islandService.islandAt(entity.getLocation()).orElse(null);
        if (island == null || island.index() != generator.islandIndex()
                || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "crop-generator-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(generator.islandIndex());
        if (core != null && core.level() >= pickupLockCoreLevel) {
            messages.send(player, "crop-generator-pickup-locked");
            return false;
        }
        UUID itemId = repository.removeAndReserveItem(generator.islandIndex(), generator.generatorId());
        if (itemId == null) {
            return false;
        }
        try {
            deliverItem(player, createItem(
                    generator.islandIndex(), itemId, generator.level(), generator.state(),
                    generator.runeSlots(), generator.runes()));
        } catch (RuntimeException exception) {
            repository.restore(generator, itemId);
            plugin.getLogger().log(Level.SEVERE, "Could not return a picked-up crop generator.", exception);
            messages.send(player, "crop-generator-pickup-failed");
            return false;
        }
        cancelTasks(generator.generatorId());
        World world = plugin.getServer().getWorld(generator.worldId());
        if (world != null) {
            removeIfPresent(world.getEntity(generator.itemDisplayId()));
            removeIfPresent(world.getEntity(generator.interactionId()));
            removeIfPresent(world.getEntity(generator.textDisplayId()));
        }
        messages.send(player, "crop-generator-picked-up");
        return true;
    }

    public CropGeneratorRecord record(UUID generatorId) {
        return repository.getById(generatorId);
    }

    public boolean canManage(Player player, CropGeneratorRecord generator) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != generator.islandIndex()
                || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "crop-generator-access-denied");
            return false;
        }
        return true;
    }

    public boolean canRemoveRunes(Player player, CropGeneratorRecord generator) {
        CoreRecord core = coreRepository.core(generator.islandIndex());
        if (core != null && core.level() >= pickupLockCoreLevel) {
            messages.send(player, "crop-generator-runes-locked");
            return false;
        }
        return canManage(player, generator);
    }

    public boolean canUseRuneTier(Player player, CropGeneratorRecord generator, int tier) {
        if (!canManage(player, generator)) {
            return false;
        }
        int maximumTier = CellRecord.maximumTierForCropGeneratorLevel(generator.level());
        if (tier > maximumTier) {
            messages.send(player, "crop-generator-rune-tier-locked", Map.of(
                    "tier", Integer.toString(tier),
                    "max_tier", Integer.toString(maximumTier),
                    "level", Integer.toString(generator.level())));
            return false;
        }
        return true;
    }

    public boolean setRune(UUID generatorId, int slot, CellRecord rune) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator == null || slot < 0 || slot >= generator.runeSlots()
                || (rune != null && !rune.type().isCropGeneratorRune())) {
            return false;
        }
        CropGeneratorRecord updated = generator.withRune(slot, rune);
        repository.update(updated);
        refreshVisuals(updated);
        return true;
    }

    public int runeSlotRequiredLevel(int slots) {
        return config.getInt("crop-generator.runes.slot-required-level." + slots, slots == 2 ? 3 : 6);
    }

    public int maximumRuneSlotsForLevel(int level) {
        if (level >= runeSlotRequiredLevel(3)) {
            return 3;
        }
        if (level >= runeSlotRequiredLevel(2)) {
            return 2;
        }
        return 1;
    }

    public String menuText(String path, String fallback) {
        return config.getString("crop-generator.menus." + path, fallback);
    }

    public List<String> menuLore(String path, List<String> fallback) {
        List<String> configured = config.getStringList("crop-generator.menus." + path);
        return configured.isEmpty() ? fallback : configured;
    }

    public Material menuMaterial(String path, String fallback) {
        Material material = Material.matchMaterial(
                config.getString("crop-generator.menus." + path, fallback));
        if (material == null || !material.isItem()) {
            throw new IllegalArgumentException("crop_generator.yml menu material is invalid: " + path);
        }
        return material;
    }

    public int menuSlot(String path, int fallback) {
        return config.getInt("crop-generator.menus." + path, fallback);
    }

    public int[] runeMenuSlots(int unlockedSlots) {
        List<Integer> configured = config.getIntegerList(
                "crop-generator.menus.runes.slot-layout." + unlockedSlots);
        if (configured.size() != unlockedSlots
                || configured.stream().anyMatch(slot -> slot < 0 || slot >= 27)) {
            throw new IllegalArgumentException(
                    "crop_generator.yml must define valid rune menu slots for " + unlockedSlots + " slots.");
        }
        return configured.stream().mapToInt(Integer::intValue).toArray();
    }

    public boolean destroyForFracture(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-fracture-owner-only");
            return false;
        }
        CropGeneratorRecord removed = repository.removeForFracture(islandIndex);
        if (removed != null) {
            cancelTasks(removed.generatorId());
            World world = plugin.getServer().getWorld(removed.worldId());
            if (world != null) {
                removeIfPresent(world.getEntity(removed.itemDisplayId()));
                removeIfPresent(world.getEntity(removed.interactionId()));
                removeIfPresent(world.getEntity(removed.textDisplayId()));
            }
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!island.canBuild(online.getUniqueId())) {
                continue;
            }
            if (online.getUniqueId().equals(player.getUniqueId())) {
                removeBoundItems(online, islandIndex);
            } else {
                online.getScheduler().run(plugin, task -> removeBoundItems(online, islandIndex), null);
            }
        }
        return true;
    }

    private void removeBoundItems(Player player, long islandIndex) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isGeneratorItem(item)) {
                continue;
            }
            Long boundIsland = item.getItemMeta().getPersistentDataContainer()
                    .get(itemIslandKey, PersistentDataType.LONG);
            if (boundIsland != null && boundIsland == islandIndex) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void cancelTasks(UUID generatorId) {
        ScheduledTask visual = visualTasks.remove(generatorId);
        if (visual != null) {
            visual.cancel();
        }
    }

    public boolean toggleEnabled(Player player, UUID generatorId) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator == null || !canManage(player, generator)) {
            return false;
        }
        CropGeneratorRecord updated = generator.withState(generator.state().nextManualState());
        repository.update(updated);
        refreshVisuals(updated);
        return true;
    }

    public boolean upgrade(Player player, UUID generatorId) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator == null || !canManage(player, generator)) {
            return false;
        }
        if (generator.level() >= MAX_LEVEL) {
            messages.send(player, "crop-generator-max-level");
            return false;
        }
        double cost = upgradeCost(generator.level());
        CoreRecord chargedCore = coreRepository.spendEnergy(generator.islandIndex(), cost);
        if (chargedCore == null) {
            messages.send(player, "crop-generator-insufficient-energy");
            return false;
        }
        int updatedLevel = generator.level() + 1;
        CropGeneratorRecord updated = generator.withLevel(updatedLevel, maximumRuneSlotsForLevel(updatedLevel));
        repository.update(updated);
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        refreshVisuals(updated);
        messages.send(player, "crop-generator-upgraded", Map.of(
                "level", Integer.toString(updated.level()),
                "cost", formatEnergy(cost)));
        return true;
    }

    public double upgradeCost(int currentLevel) {
        return upgradeBaseCost * Math.pow(upgradeCostMultiplier, currentLevel);
    }

    public double energyCost(CropGeneratorRecord generator) {
        double cost = baseEnergyCost + energyCostPerLevel * generator.level();
        double reduction = generator.activeRunes().values().stream()
                .mapToDouble(CellRecord::cropEnergyReduction)
                .sum();
        return Math.max(0.0, cost * (1.0 - Math.min(0.9, reduction)));
    }

    public double growthBonusPercent(CropGeneratorRecord generator) {
        return growthBonusPercent(generator.level());
    }

    public double growthBonusPercent(int level) {
        return (baseGrowthStages + growthStagesPerLevel * level) * 100.0;
    }

    public double energyIntervalSeconds() {
        return energyIntervalTicks / 20.0;
    }

    public double harvestBonus(CropGeneratorRecord generator) {
        double runeBonus = generator.activeRunes().values().stream()
                .filter(rune -> rune.type() == CellType.CROP_EFFICIENCY)
                .mapToDouble(CellRecord::cropHarvestBonus)
                .sum();
        return Math.min(maximumHarvestBonus, runeBonus);
    }

    public double harvestBonusPercent(CropGeneratorRecord generator) {
        return harvestBonus(generator) * 100.0;
    }

    public String formatEnergy(double energy) {
        if (energy >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", energy / 1_000_000);
        }
        if (energy >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", energy / 1_000);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", energy);
    }

    private void refreshVisuals(CropGeneratorRecord generator) {
        World world = plugin.getServer().getWorld(generator.worldId());
        if (world == null) {
            return;
        }
        double blockY = Math.floor(generator.y()) - 1;
        Location location = new Location(world, generator.x(), blockY + displayHeightOffset, generator.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            Entity displayEntity = world.getEntity(generator.itemDisplayId());
            if (displayEntity instanceof ItemDisplay display) {
                display.teleport(location);
                configureDisplay(display);
                startVisualAnimation(display, generator.generatorId());
            }
            Entity interactionEntity = world.getEntity(generator.interactionId());
            if (interactionEntity instanceof Interaction interaction) {
                interaction.teleport(location.clone().subtract(0, 0.6, 0));
            }
            Entity textEntity = world.getEntity(generator.textDisplayId());
            if (textEntity instanceof TextDisplay display) {
                display.teleport(location.clone().add(0, 1.1, 0));
                display.text(hologram(generator));
            }
        });
    }

    private void configureDisplay(ItemDisplay display) {
        display.setItemStack(new ItemStack(itemMaterial));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        Transformation current = display.getTransformation();
        display.setTransformation(new Transformation(
                new Vector3f(current.getTranslation()),
                new Quaternionf(current.getLeftRotation()),
                new Vector3f((float) displayScale, (float) displayScale, (float) displayScale),
                new Quaternionf(current.getRightRotation())));
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(true);
    }

    private void startVisualAnimation(ItemDisplay display, UUID generatorId) {
        if (visualTasks.containsKey(generatorId)) {
            return;
        }
        CropGeneratorRecord initialRecord = repository.getById(generatorId);
        if (initialRecord == null) {
            return;
        }
        long islandIndex = initialRecord.islandIndex();
        float[] yaw = {0.0f};
        long[] ticksSinceCharge = {0L};
        long[] ticksSinceParticle = {0L};
        ScheduledTask[] taskReference = new ScheduledTask[1];
        ScheduledTask task = display.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!display.isValid()) {
                scheduled.cancel();
                visualTasks.remove(generatorId, taskReference[0]);
                return;
            }
            yaw[0] += (float) (Math.PI * 2.0 * rotationPeriodTicks / (20.0 * 8.0));
            Transformation current = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(rotationPeriodTicks);
            display.setTransformation(new Transformation(
                    new Vector3f(current.getTranslation()),
                    new Quaternionf(current.getLeftRotation()),
                    new Vector3f(current.getScale()),
                    new Quaternionf().rotateY(yaw[0])));
            CropGeneratorRecord generator = repository.get(islandIndex);
            if (generator == null || !generator.generatorId().equals(generatorId)) {
                scheduled.cancel();
                visualTasks.remove(generatorId, taskReference[0]);
                return;
            }
            ticksSinceCharge[0] += rotationPeriodTicks;
            long chargeCheckInterval = generator.state() == CondenserState.IDLE
                    ? Math.min(IDLE_RETRY_INTERVAL_TICKS, energyIntervalTicks)
                    : energyIntervalTicks;
            if (ticksSinceCharge[0] >= chargeCheckInterval) {
                ticksSinceCharge[0] %= chargeCheckInterval;
                chargeGenerator(generator);
                generator = repository.get(islandIndex);
            }
            ticksSinceParticle[0] += rotationPeriodTicks;
            if (ticksSinceParticle[0] >= 100L) {
                ticksSinceParticle[0] %= 100L;
            } else {
                return;
            }
            if (generator != null && generator.state() == CondenserState.ACTIVE) {
                display.getWorld().spawnParticle(
                        Particle.COMPOSTER, display.getLocation().add(0, 0.15, 0), 2, 0.16, 0.1, 0.16, 0.01);
            }
        }, () -> visualTasks.remove(generatorId, taskReference[0]), 1L, rotationPeriodTicks);
        if (task == null) {
            return;
        }
        taskReference[0] = task;
        ScheduledTask previous = visualTasks.putIfAbsent(generatorId, task);
        if (previous != null) {
            task.cancel();
        }
    }

    private void chargeGenerator(CropGeneratorRecord generator) {
        if (generator.state() == CondenserState.DISABLED) {
            return;
        }
        CoreRecord chargedCore = coreRepository.spendEnergy(
                generator.islandIndex(), energyCost(generator));
        if (chargedCore == null) {
            if (generator.state() != CondenserState.IDLE) {
                CropGeneratorRecord idle = generator.withState(CondenserState.IDLE);
                repository.update(idle);
                refreshVisuals(idle);
            }
            return;
        }
        if (generator.state() == CondenserState.IDLE) {
            CropGeneratorRecord active = generator.withState(CondenserState.ACTIVE);
            repository.update(active);
            refreshVisuals(active);
        }
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
    }

    private Component hologram(CropGeneratorRecord generator) {
        String color = switch (generator.state()) {
            case ACTIVE -> "&a";
            case IDLE -> "&e";
            case DISABLED -> "&c";
        };
        return messages.parseComponent("&aCatalizador de Cultivos &7[Nvl. " + generator.level() + "]")
                .append(Component.newline())
                .append(messages.parseComponent("&7Estado: " + color + stateLabel(generator.state())))
                .append(Component.newline())
                .append(messages.parseComponent("&7Aceleración: &e+" + formatEnergy(growthBonusPercent(generator))
                        + "%"))
                .append(Component.newline())
                .append(messages.parseComponent("&7Consumo: &6" + formatEnergy(energyCost(generator))
                        + "⚡/&e" + formatEnergy(energyIntervalTicks / 20.0) + "s"));
    }

    private String stateLabel(CondenserState state) {
        return state == CondenserState.DISABLED ? "DISABLE" : state.name();
    }

    private void removeIfPresent(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private CondenserState parseState(String state) {
        try {
            return CondenserState.valueOf(state);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public void flush() throws IOException {
        repository.flush();
    }
}
