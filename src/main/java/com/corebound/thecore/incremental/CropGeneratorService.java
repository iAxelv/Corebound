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
import java.util.ArrayList;
import java.util.HashMap;
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
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Sapling;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CropGeneratorService implements Listener {
    private static final long RANGE_GUIDE_PERIOD_TICKS = 12L;
    private static final long VISUAL_UPDATE_PERIOD_TICKS = 10L;
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
    private static final Set<Material> STACKING_CROPS = Set.of(
            Material.CACTUS,
            Material.SUGAR_CANE,
            Material.BAMBOO,
            Material.BAMBOO_SAPLING,
            Material.KELP,
            Material.KELP_PLANT);

    private record CropPosition(UUID worldId, int x, int y, int z) {
    }

    private record CropChunk(UUID worldId, int x, int z) {
    }

    private static final class CropCandidates {
        private final List<CropPosition> positions = new ArrayList<>();
        private final Map<CropPosition, Integer> indexes = new HashMap<>();

        private synchronized void add(CropPosition position) {
            if (!indexes.containsKey(position)) {
                indexes.put(position, positions.size());
                positions.add(position);
            }
        }

        private synchronized void remove(CropPosition position) {
            Integer index = indexes.remove(position);
            if (index == null) {
                return;
            }
            int lastIndex = positions.size() - 1;
            CropPosition last = positions.remove(lastIndex);
            if (index != lastIndex) {
                positions.set(index, last);
                indexes.put(last, index);
            }
        }

        private synchronized CropPosition randomDifferentColumn(CropPosition previous) {
            if (positions.isEmpty()) {
                return null;
            }
            CropPosition selected = positions.get(ThreadLocalRandom.current().nextInt(positions.size()));
            if (previous == null || !sameColumn(selected, previous)) {
                return selected;
            }

            CropPosition alternative = null;
            int alternativeCount = 0;
            for (CropPosition position : positions) {
                if (!sameColumn(position, previous)
                        && ThreadLocalRandom.current().nextInt(++alternativeCount) == 0) {
                    alternative = position;
                }
            }
            return alternative == null ? selected : alternative;
        }

        private boolean sameColumn(CropPosition first, CropPosition second) {
            return first.worldId().equals(second.worldId())
                    && first.x() == second.x()
                    && first.z() == second.z();
        }
    }

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
    private final Map<UUID, Long> shotCountdownTicks = new ConcurrentHashMap<>();
    private final Map<UUID, CropPosition> lastShotPositions = new ConcurrentHashMap<>();
    private final Map<Long, CropCandidates> cropCandidates = new ConcurrentHashMap<>();
    private final Set<CropChunk> cropScansInProgress = ConcurrentHashMap.newKeySet();
    private final Set<UUID> initialCropScans = ConcurrentHashMap.newKeySet();
    private final Set<UUID> growthShotsInProgress = ConcurrentHashMap.newKeySet();
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
    private double baseShotDelaySeconds;
    private double shotDelayReductionPerLevelSeconds;
    private double minimumShotDelaySeconds;
    private double maximumHarvestBonus;
    private int pickupLockCoreLevel;
    private Sound growthSound;
    private float growthSoundVolume;
    private float growthSoundPitch;

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
            shotCountdownTicks.put(generator.generatorId(), shotDelayTicks(generator));
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
        double loadedBaseEnergyCost =
                loaded.getDouble("crop-generator.energy.cost-per-shot.base", 1.0);
        double loadedEnergyCostPerLevel =
                loaded.getDouble("crop-generator.energy.cost-per-shot.per-level", 0.25);
        double loadedBaseShotDelaySeconds =
                loaded.getDouble("crop-generator.growth.shot-delay.base-seconds", 6.0);
        double loadedShotDelayReductionPerLevelSeconds =
                loaded.getDouble("crop-generator.growth.shot-delay.reduction-per-level-seconds", 0.5);
        double loadedMinimumShotDelaySeconds =
                loaded.getDouble("crop-generator.growth.shot-delay.minimum-seconds", 2.5);
        Sound loadedGrowthSound = configuredSound(
                loaded, "crop-generator.growth.feedback.sound", "item.bone_meal.use");
        double loadedGrowthSoundVolume =
                loaded.getDouble("crop-generator.growth.feedback.volume", 0.8);
        double loadedGrowthSoundPitch =
                loaded.getDouble("crop-generator.growth.feedback.pitch", 1.1);
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
                || !positiveFinite(loadedBaseShotDelaySeconds)
                || !Double.isFinite(loadedShotDelayReductionPerLevelSeconds)
                || loadedShotDelayReductionPerLevelSeconds < 0
                || !positiveFinite(loadedMinimumShotDelaySeconds)
                || loadedMinimumShotDelaySeconds > loadedBaseShotDelaySeconds
                || !validSoundSetting(loadedGrowthSoundVolume)
                || !validSoundSetting(loadedGrowthSoundPitch)
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
        baseShotDelaySeconds = loadedBaseShotDelaySeconds;
        shotDelayReductionPerLevelSeconds = loadedShotDelayReductionPerLevelSeconds;
        minimumShotDelaySeconds = loadedMinimumShotDelaySeconds;
        growthSound = loadedGrowthSound;
        growthSoundVolume = (float) loadedGrowthSoundVolume;
        growthSoundPitch = (float) loadedGrowthSoundPitch;
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

    private Sound configuredSound(YamlConfiguration loaded, String path, String fallback) {
        String value = loaded.getString(path, fallback);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("crop_generator.yml " + path + " must not be empty.");
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":")
                ? normalized
                : "minecraft:" + normalized);
        Sound sound = key == null ? null : Registry.SOUNDS.get(key);
        if (sound == null) {
            throw new IllegalArgumentException("crop_generator.yml " + path + " must be a valid sound key.");
        }
        return sound;
    }

    private boolean validSoundSetting(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 2.0;
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
        meta.displayName(messages.component("crop-generator-ui.placed-name"));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("crop-generator-ui.level", Map.of(
                        "level", Integer.toString(level),
                        "max_level", Integer.toString(MAX_LEVEL)))),
                messages.parseComponent(messages.formatText("crop-generator-ui.state", Map.of(
                        "state", stateLabel(state)))),
                messages.component("crop-generator-ui.description"),
                messages.component("crop-generator-ui.island-limit"),
                messages.component("crop-generator-ui.pickup")));
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
            scanLoadedIslandCrops(generator);
            refreshVisuals(generator);
            consumeHeldItem(player, heldItem);
            startVisualAnimationFor(generatorId);
            startVisualAnimation(itemDisplay, generatorId);
            messages.send(player, "crop-generator-placed");
            return true;
        } catch (RuntimeException exception) {
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
                scanLoadedIslandCrops(generator);
                Entity entity = world.getEntity(generator.interactionId());
                if (entity instanceof Interaction) {
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
        scanLoadedIslandCrops(generator);
        if (entity instanceof ItemDisplay display) {
            configureDisplay(display);
            startVisualAnimation(display, generator.generatorId());
        } else if (entity instanceof Interaction) {
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

    @EventHandler(ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        trackCrop(event.getNewState());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropPlace(BlockPlaceEvent event) {
        trackCrop(event.getBlockPlaced());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropFertilize(BlockFertilizeEvent event) {
        event.getBlocks().forEach(this::trackCrop);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropBreak(BlockBreakEvent event) {
        untrackCrop(event.getBlock());
        refreshCropCandidatesAround(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropHarvest(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (isTrackedPlant(block.getType())) {
            refreshCropCandidatesAround(block);
        }
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

    @EventHandler(ignoreCancelled = true)
    public void onCropHarvestInPlace(PlayerHarvestBlockEvent event) {
        Block block = event.getHarvestedBlock();
        if (isTrackedPlant(block.getType())) {
            refreshCropCandidatesAround(block);
        }
    }

    @EventHandler
    public void onCropChunkLoad(ChunkLoadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        IslandData island = null;
        for (int offsetX : new int[] {0, 15}) {
            for (int offsetZ : new int[] {0, 15}) {
                island = islandService.islandAt(new Location(
                        chunk.getWorld(), baseX + offsetX, chunk.getWorld().getMinHeight(), baseZ + offsetZ))
                        .orElse(null);
                if (island != null) {
                    break;
                }
            }
            if (island != null) {
                break;
            }
        }
        if (island == null) {
            return;
        }
        CropGeneratorRecord generator = repository.get(island.index());
        if (generator != null && generator.state() != CondenserState.DISABLED) {
            scheduleChunkCropScan(generator, island, chunk.getWorld(), chunk.getX(), chunk.getZ());
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

    private void trackCrop(Block block) {
        if (!isCropCandidate(block.getType(), block.getBlockData(), block)) {
            return;
        }
        IslandData island = islandService.islandAt(block.getLocation()).orElse(null);
        if (island != null) {
            trackCrop(block, island.index());
        }
    }

    private void trackCrop(BlockState state) {
        Block block = state.getBlock();
        if (!isCropCandidate(state.getType(), state.getBlockData(), block)) {
            untrackCrop(block);
            return;
        }
        IslandData island = islandService.islandAt(block.getLocation()).orElse(null);
        if (island != null) {
            addCropCandidate(island.index(), block);
        }
    }

    private void trackCrop(Block block, long islandIndex) {
        if (!isCropCandidate(block.getType(), block.getBlockData(), block)) {
            return;
        }
        addCropCandidate(islandIndex, block);
    }

    private boolean isCropCandidate(Material type, BlockData data, Block block) {
        boolean immatureCrop = CROPS.contains(type) && data instanceof Ageable ageable
                && ageable.getAge() < ageable.getMaximumAge();
        boolean stackingCrop = STACKING_CROPS.contains(type) && isTopStackingPlant(type, block);
        boolean sapling = isSapling(type) && data instanceof Sapling cropSapling
                && cropSapling.getStage() <= cropSapling.getMaximumStage();
        return immatureCrop || stackingCrop || sapling;
    }

    private boolean isTrackedPlant(Material type) {
        return CROPS.contains(type) || STACKING_CROPS.contains(type) || isSapling(type);
    }

    private void addCropCandidate(long islandIndex, Block block) {
        if (repository.get(islandIndex) == null) {
            return;
        }
        cropCandidates.computeIfAbsent(islandIndex, ignored -> new CropCandidates())
                .add(cropPosition(block));
    }

    private void untrackCrop(Block block) {
        if (!isTrackedPlant(block.getType())) {
            return;
        }
        IslandData island = islandService.islandAt(block.getLocation()).orElse(null);
        if (island == null) {
            return;
        }
        CropCandidates candidates = cropCandidates.get(island.index());
        if (candidates != null) {
            candidates.remove(cropPosition(block));
        }
    }

    private void refreshCropCandidatesAround(Block block) {
        World world = block.getWorld();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                int x = block.getX() + offsetX;
                int z = block.getZ() + offsetZ;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Location location = new Location(world, x, block.getY(), z);
                plugin.getServer().getRegionScheduler().runDelayed(plugin, location, task -> {
                    for (int offsetY = -3; offsetY <= 3; offsetY++) {
                        int y = block.getY() + offsetY;
                        if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
                            trackCrop(world.getBlockAt(x, y, z));
                        }
                    }
                }, 1L);
            }
        }
    }

    private void scanLoadedIslandCrops(CropGeneratorRecord generator) {
        if (generator.state() == CondenserState.DISABLED
                || !initialCropScans.add(generator.generatorId())) {
            return;
        }
        World world = plugin.getServer().getWorld(generator.worldId());
        if (world == null) {
            initialCropScans.remove(generator.generatorId());
            return;
        }
        IslandData island = islandService.islandAt(
                new Location(world, generator.x(), generator.y(), generator.z())).orElse(null);
        if (island == null) {
            initialCropScans.remove(generator.generatorId());
            return;
        }

        int radius = (int) Math.ceil(islandService.islandRadius());
        int minChunkX = Math.floorDiv((int) (island.centerX() - radius), 16);
        int maxChunkX = Math.floorDiv((int) (island.centerX() + radius), 16);
        int minChunkZ = Math.floorDiv((int) (island.centerZ() - radius), 16);
        int maxChunkZ = Math.floorDiv((int) (island.centerZ() + radius), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    scheduleChunkCropScan(generator, island, world, chunkX, chunkZ);
                }
            }
        }
    }

    private void scheduleChunkCropScan(
            CropGeneratorRecord generator, IslandData island, World world, int chunkX, int chunkZ) {
        CropChunk cropChunk = new CropChunk(world.getUID(), chunkX, chunkZ);
        if (!cropScansInProgress.add(cropChunk)) {
            return;
        }
        int radius = (int) Math.ceil(islandService.islandRadius());
        int minX = (int) (island.centerX() - radius);
        int maxX = (int) (island.centerX() + radius);
        int minZ = (int) (island.centerZ() - radius);
        int maxZ = (int) (island.centerZ() + radius);
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        int[] nextColumn = {0};
        Location schedulerLocation = new Location(world, startX + 8, world.getMinHeight(), startZ + 8);
        ScheduledTask scanTask = plugin.getServer().getRegionScheduler().runAtFixedRate(
                plugin,
                schedulerLocation,
                task -> {
                    CropGeneratorRecord current = repository.getById(generator.generatorId());
                    if (!world.isChunkLoaded(chunkX, chunkZ) || current == null) {
                        task.cancel();
                        cropScansInProgress.remove(cropChunk);
                        if (current == null) {
                            initialCropScans.remove(generator.generatorId());
                        }
                        return;
                    }
                    int endColumn = Math.min(256, nextColumn[0] + 4);
                    for (; nextColumn[0] < endColumn; nextColumn[0]++) {
                        int x = startX + (nextColumn[0] & 15);
                        int z = startZ + (nextColumn[0] >> 4);
                        if (x < minX || x > maxX || z < minZ || z > maxZ) {
                            continue;
                        }
                        for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                            trackCrop(world.getBlockAt(x, y, z), generator.islandIndex());
                        }
                    }
                    if (nextColumn[0] >= 256) {
                        task.cancel();
                        cropScansInProgress.remove(cropChunk);
                    }
                },
                1L,
                1L);
        if (scanTask == null) {
            cropScansInProgress.remove(cropChunk);
        }
    }

    private CropPosition cropPosition(Block block) {
        return new CropPosition(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    private boolean isSapling(Material material) {
        String name = material.name();
        return (name.endsWith("_SAPLING") && material != Material.BAMBOO_SAPLING)
                || material == Material.MANGROVE_PROPAGULE
                || material == Material.AZALEA
                || material == Material.FLOWERING_AZALEA;
    }

    private boolean isTopStackingPlant(Block block) {
        return isTopStackingPlant(block.getType(), block);
    }

    private boolean isTopStackingPlant(Material type, Block block) {
        Material above = block.getRelative(org.bukkit.block.BlockFace.UP).getType();
        if (type == Material.KELP || type == Material.KELP_PLANT) {
            return above == Material.WATER;
        }
        return above.isAir() && stackingPlantHeight(block, type) < maximumStackingPlantHeight(type);
    }

    private int maximumStackingPlantHeight(Material type) {
        if (type == Material.SUGAR_CANE || type == Material.CACTUS) {
            return 3;
        }
        return type == Material.BAMBOO_SAPLING ? 1 : Integer.MAX_VALUE;
    }

    private int stackingPlantHeight(Block top, Material type) {
        int height = 0;
        Block current = top;
        while (current.getType() == type && height < maximumStackingPlantHeight(type)) {
            height++;
            current = current.getRelative(org.bukkit.block.BlockFace.DOWN);
        }
        return height;
    }

    private void applyGrowthShot(CropGeneratorRecord expected, CropPosition position) {
        World world = plugin.getServer().getWorld(position.worldId());
        if (world == null) {
            growthShotsInProgress.remove(expected.generatorId());
            return;
        }
        Location target = new Location(world, position.x(), position.y(), position.z());
        ScheduledTask growthTask = plugin.getServer().getRegionScheduler().run(plugin, target, task -> {
            try {
                if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
                    CropCandidates candidates = cropCandidates.get(expected.islandIndex());
                    if (candidates != null) {
                        candidates.remove(position);
                    }
                    return;
                }
                CropGeneratorRecord generator = repository.getById(expected.generatorId());
                if (generator == null || generator.state() == CondenserState.DISABLED) {
                    return;
                }
                Block block = world.getBlockAt(position.x(), position.y(), position.z());
                Material type = block.getType();
                BlockData data = block.getBlockData();
                boolean ageableCrop = CROPS.contains(type) && data instanceof Ageable ageable
                        && ageable.getAge() < ageable.getMaximumAge();
                boolean stackingCrop = STACKING_CROPS.contains(type) && isTopStackingPlant(block);
                boolean saplingCrop = isSapling(type) && data instanceof Sapling sapling;
                if (!ageableCrop && !stackingCrop && !saplingCrop) {
                    CropCandidates candidates = cropCandidates.get(generator.islandIndex());
                    if (candidates != null) {
                        candidates.remove(position);
                    }
                    return;
                }
                IslandData island = islandService.islandAt(target).orElse(null);
                if (island == null || island.index() != generator.islandIndex()) {
                    CropCandidates candidates = cropCandidates.get(generator.islandIndex());
                    if (candidates != null) {
                        candidates.remove(position);
                    }
                    return;
                }
                if (saplingCrop) {
                    growSapling(generator, block, (Sapling) data, position);
                } else if (stackingCrop) {
                    growStackingCrop(generator, block, position);
                } else if (ageableCrop) {
                    growAgeableCrop(generator, block, (Ageable) data, position);
                }
            } finally {
                growthShotsInProgress.remove(expected.generatorId());
            }
        });
        if (growthTask == null) {
            growthShotsInProgress.remove(expected.generatorId());
        }
    }

    private void growAgeableCrop(
            CropGeneratorRecord generator, Block block, Ageable crop, CropPosition position) {
        if (!chargeGrowthShot(generator)) {
            return;
        }
        crop.setAge(crop.getAge() + 1);
        block.setBlockData(crop, false);
        if (crop.getAge() >= crop.getMaximumAge()) {
            removeCropCandidate(generator.islandIndex(), position);
        }
        showGrowthFeedback(generator, block.getLocation());
    }

    private void growStackingCrop(CropGeneratorRecord generator, Block block, CropPosition position) {
        Material type = block.getType();
        Block destination = block.getRelative(org.bukkit.block.BlockFace.UP);
        Material destinationType = destination.getType();
        boolean kelp = type == Material.KELP || type == Material.KELP_PLANT;
        if (kelp ? destinationType != Material.WATER : !destinationType.isAir()) {
            removeCropCandidate(generator.islandIndex(), position);
            return;
        }
        if (type == Material.CACTUS && hasSolidNeighbor(destination)) {
            removeCropCandidate(generator.islandIndex(), position);
            return;
        }
        IslandData island = islandService.islandAt(destination.getLocation()).orElse(null);
        if (island == null || island.index() != generator.islandIndex() || !chargeGrowthShot(generator)) {
            return;
        }
        if (type == Material.BAMBOO_SAPLING) {
            block.setType(Material.BAMBOO, false);
            destination.setType(Material.BAMBOO, false);
        } else {
            destination.setBlockData(type.createBlockData(), false);
        }
        removeCropCandidate(generator.islandIndex(), position);
        trackCrop(destination);
        showGrowthFeedback(generator, destination.getLocation());
    }

    private boolean hasSolidNeighbor(Block block) {
        for (org.bukkit.block.BlockFace face : List.of(
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST)) {
            if (block.getRelative(face).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    private void growSapling(
            CropGeneratorRecord generator, Block block, Sapling sapling, CropPosition position) {
        if (sapling.getStage() < sapling.getMaximumStage()) {
            if (!chargeGrowthShot(generator)) {
                return;
            }
            sapling.setStage(sapling.getStage() + 1);
            block.setBlockData(sapling, false);
            showGrowthFeedback(generator, block.getLocation());
            return;
        }
        TreeType treeType = treeType(block.getType());
        if (treeType == null || !canGrowTree(generator, block.getLocation())
                || !chargeGrowthShot(generator)) {
            return;
        }
        if (!block.getWorld().generateTree(block.getLocation(), treeType)) {
            coreRepository.restoreEnergy(generator.islandIndex(), energyCost(generator));
            CoreRecord restoredCore = coreRepository.core(generator.islandIndex());
            if (restoredCore != null) {
                coreService.refreshCore(restoredCore, System.currentTimeMillis());
            }
            return;
        }
        removeCropCandidate(generator.islandIndex(), position);
        showGrowthFeedback(generator, block.getLocation());
    }

    private TreeType treeType(Material material) {
        return switch (material.name()) {
            case "BIRCH_SAPLING" -> TreeType.BIRCH;
            case "SPRUCE_SAPLING" -> TreeType.REDWOOD;
            case "JUNGLE_SAPLING" -> TreeType.SMALL_JUNGLE;
            case "ACACIA_SAPLING" -> TreeType.ACACIA;
            case "DARK_OAK_SAPLING" -> TreeType.DARK_OAK;
            case "CHERRY_SAPLING" -> TreeType.CHERRY;
            case "MANGROVE_PROPAGULE" -> TreeType.MANGROVE;
            case "AZALEA", "FLOWERING_AZALEA" -> TreeType.AZALEA;
            case "PALE_OAK_SAPLING" -> TreeType.PALE_OAK;
            default -> TreeType.TREE;
        };
    }

    private boolean canGrowTree(CropGeneratorRecord generator, Location location) {
        IslandData island = islandService.islandAt(location).orElse(null);
        return island != null && island.index() == generator.islandIndex();
    }

    private void removeCropCandidate(long islandIndex, CropPosition position) {
        CropCandidates candidates = cropCandidates.get(islandIndex);
        if (candidates != null) {
            candidates.remove(position);
        }
    }

    private boolean chargeGrowthShot(CropGeneratorRecord generator) {
        CoreRecord chargedCore = coreRepository.spendEnergy(
                generator.islandIndex(), energyCost(generator));
        if (chargedCore == null) {
            if (generator.state() != CondenserState.IDLE) {
                CropGeneratorRecord idle = generator.withState(CondenserState.IDLE);
                repository.update(idle);
                refreshVisuals(idle);
            }
            return false;
        }
        if (generator.state() == CondenserState.IDLE) {
            CropGeneratorRecord active = generator.withState(CondenserState.ACTIVE);
            repository.update(active);
            refreshVisuals(active);
        }
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        return true;
    }

    private void attemptGrowthShot(CropGeneratorRecord generator) {
        if (generator.state() == CondenserState.DISABLED || !growthShotsInProgress.add(generator.generatorId())) {
            return;
        }
        CropCandidates candidates = cropCandidates.get(generator.islandIndex());
        CropPosition position = candidates == null
                ? null
                : candidates.randomDifferentColumn(lastShotPositions.get(generator.generatorId()));
        if (position == null) {
            growthShotsInProgress.remove(generator.generatorId());
            return;
        }
        lastShotPositions.put(generator.generatorId(), position);
        applyGrowthShot(generator, position);
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
        cropLocation.getWorld().playSound(cropLocation, growthSound, growthSoundVolume, growthSoundPitch);
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

    public String menuText(String path) {
        String text = config.getString("crop-generator.menus." + path);
        if (text == null) {
            throw new IllegalArgumentException("Missing crop generator menu text: " + path);
        }
        return text;
    }

    public List<String> menuLore(String path) {
        if (!config.isList("crop-generator.menus." + path)) {
            throw new IllegalArgumentException("Missing crop generator menu lore: " + path);
        }
        return config.getStringList("crop-generator.menus." + path);
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
        cropCandidates.remove(islandIndex);
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
        shotCountdownTicks.remove(generatorId);
        lastShotPositions.remove(generatorId);
        initialCropScans.remove(generatorId);
        growthShotsInProgress.remove(generatorId);
    }

    public boolean toggleEnabled(Player player, UUID generatorId) {
        CropGeneratorRecord generator = repository.getById(generatorId);
        if (generator == null || !canManage(player, generator)) {
            return false;
        }
        CropGeneratorRecord updated = generator.withState(generator.state().nextManualState());
        repository.update(updated);
        scanLoadedIslandCrops(updated);
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

    public double shotDelaySeconds(CropGeneratorRecord generator) {
        return shotDelaySeconds(generator.level());
    }

    public double shotDelaySeconds(int level) {
        return CropGeneratorMath.shotDelaySeconds(
                level, baseShotDelaySeconds, shotDelayReductionPerLevelSeconds, minimumShotDelaySeconds);
    }

    private long shotDelayTicks(CropGeneratorRecord generator) {
        return Math.max(1L, Math.round(shotDelaySeconds(generator) * 20.0));
    }

    private long remainingShotTicks(CropGeneratorRecord generator) {
        return Math.min(shotCountdownTicks.getOrDefault(
                generator.generatorId(), shotDelayTicks(generator)), shotDelayTicks(generator));
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
        long animationIntervalTicks = Math.min(VISUAL_UPDATE_PERIOD_TICKS, rotationPeriodTicks);
        float[] yaw = {0.0f};
        shotCountdownTicks.putIfAbsent(generatorId, shotDelayTicks(initialRecord));
        long[] ticksSinceParticle = {0L};
        ScheduledTask[] taskReference = new ScheduledTask[1];
        ScheduledTask task = display.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!display.isValid()) {
                scheduled.cancel();
                visualTasks.remove(generatorId, taskReference[0]);
                return;
            }
            yaw[0] += (float) (Math.PI * 2.0 * animationIntervalTicks / (20.0 * 8.0));
            Transformation current = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration((int) animationIntervalTicks);
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
            if (generator.state() != CondenserState.DISABLED) {
                long delayTicks = shotDelayTicks(generator);
                long remainingTicks = Math.min(
                        shotCountdownTicks.getOrDefault(generatorId, delayTicks), delayTicks);
                remainingTicks = Math.max(0L, remainingTicks - animationIntervalTicks);
                if (remainingTicks == 0L) {
                    attemptGrowthShot(generator);
                    remainingTicks = delayTicks;
                }
                shotCountdownTicks.put(generatorId, remainingTicks);
            }
            Entity textEntity = display.getWorld().getEntity(generator.textDisplayId());
            if (textEntity instanceof TextDisplay textDisplay) {
                textDisplay.text(hologram(generator));
            }
            ticksSinceParticle[0] += animationIntervalTicks;
            if (ticksSinceParticle[0] >= 100L) {
                ticksSinceParticle[0] %= 100L;
            } else {
                return;
            }
            if (generator != null && generator.state() == CondenserState.ACTIVE) {
                display.getWorld().spawnParticle(
                        Particle.COMPOSTER, display.getLocation().add(0, 0.15, 0), 2, 0.16, 0.1, 0.16, 0.01);
            }
        }, () -> visualTasks.remove(generatorId, taskReference[0]),
                animationIntervalTicks, animationIntervalTicks);
        if (task == null) {
            return;
        }
        taskReference[0] = task;
        ScheduledTask previous = visualTasks.putIfAbsent(generatorId, task);
        if (previous != null) {
            task.cancel();
        }
    }

    private Component hologram(CropGeneratorRecord generator) {
        return messages.parseComponent(messages.formatText("crop-generator-ui.hologram-name", Map.of(
                        "level", Integer.toString(generator.level()))))
                .append(Component.newline())
                .append(messages.parseComponent(messages.formatText("crop-generator-ui.hologram-state", Map.of(
                        "state", stateLabel(generator.state())))))
                .append(Component.newline())
                .append(messages.parseComponent(messages.formatText("crop-generator-ui.hologram-shot", Map.of(
                        "seconds", CropGeneratorMath.formatSeconds(remainingShotTicks(generator) / 20.0)))))
                .append(Component.newline())
                .append(messages.parseComponent(messages.formatText("crop-generator-ui.hologram-cost", Map.of(
                        "cost", formatEnergy(energyCost(generator))))));
    }

    private String stateLabel(CondenserState state) {
        return menuText("main.state." + state.name().toLowerCase(java.util.Locale.ROOT));
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
