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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class BatteryService {
    private static final long OFFLINE_UPDATE_SECONDS = 60L;
    private static final long RANGE_GUIDE_PERIOD_TICKS = 12L;
    private static final double OFFLINE_EFFICIENCY = 0.75;
    private static final int MAX_TIER = 3;
    private static final int MAX_RUNES = 3;

    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final CoreService coreService;
    private final CoreRepository coreRepository;
    private final BatteryRepository repository;
    private final PylonService pylons;
    private final Messages messages;
    private final File configFile;
    private final NamespacedKey itemKey;
    private final NamespacedKey itemIslandKey;
    private final NamespacedKey itemTierKey;
    private final NamespacedKey itemEnergyKey;
    private final List<NamespacedKey> itemRuneTypeKeys;
    private final List<NamespacedKey> itemRuneTierKeys;
    private final NamespacedKey entityKey;
    private final NamespacedKey entityIdKey;
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> rangeGuidePlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> animationTasks = new ConcurrentHashMap<>();
    private final BatteryMenu menu;
    private volatile YamlConfiguration config;
    private Material itemMaterial;
    private double purchaseCost;
    private double minDistance;
    private double maxDistance;
    private int minimumCoreLevel;
    private int pickupLockCoreLevel;
    private double capacityRuneBonusPerTier;
    private double offlineRuneBonusPerTier;
    private volatile double offlineEfficiency;
    private volatile long offlineUpdateSeconds;
    private volatile int baseOfflineHours;
    private volatile int maximumOfflineHours;
    private ScheduledTask offlineTask;

    public BatteryService(
            TheCorePlugin plugin,
            IslandService islandService,
            CoreService coreService,
            CoreRepository coreRepository,
            BatteryRepository repository,
            PylonService pylons,
            Messages messages,
            File configFile) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.coreService = coreService;
        this.coreRepository = coreRepository;
        this.repository = repository;
        this.pylons = pylons;
        this.messages = messages;
        this.configFile = configFile;
        itemKey = new NamespacedKey(plugin, "resonance_battery_item");
        itemIslandKey = new NamespacedKey(plugin, "resonance_battery_island");
        itemTierKey = new NamespacedKey(plugin, "resonance_battery_tier");
        itemEnergyKey = new NamespacedKey(plugin, "resonance_battery_energy");
        itemRuneTypeKeys = java.util.stream.IntStream.range(0, MAX_RUNES)
                .mapToObj(index -> new NamespacedKey(plugin, "resonance_battery_rune_" + index + "_type"))
                .toList();
        itemRuneTierKeys = java.util.stream.IntStream.range(0, MAX_RUNES)
                .mapToObj(index -> new NamespacedKey(plugin, "resonance_battery_rune_" + index + "_tier"))
                .toList();
        entityKey = new NamespacedKey(plugin, "resonance_battery_entity");
        entityIdKey = new NamespacedKey(plugin, "resonance_battery_id");
        applyConfiguration(loadConfiguration(true));
        menu = new BatteryMenu(messages, this);
        plugin.getServer().getOnlinePlayers().forEach(player -> onlinePlayers.add(player.getUniqueId()));
        scheduleOfflineTask();
    }

    private YamlConfiguration loadConfiguration(boolean saveDefaults) {
        YamlConfiguration loaded = ConfigFiles.load(configFile);
        InputStream defaults = plugin.getResource("core/battery.yml");
        if (defaults == null) {
            throw new IllegalArgumentException("Bundled core/battery.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/battery.yml defaults.", exception);
        }
        return loaded;
    }

    private void applyConfiguration(YamlConfiguration loaded) {
        Material configuredMaterial = Material.matchMaterial(loaded.getString("battery.item.material", "COPPER_BLOCK"));
        double configuredCost = loaded.getDouble("battery.purchase-cost", 1000.0);
        double configuredMinimumDistance = loaded.getDouble("battery.min-distance", pylons.minimumDistance());
        double configuredMaximumDistance = loaded.getDouble("battery.max-distance", pylons.maximumDistance());
        int configuredMinimumCoreLevel = loaded.getInt("battery.minimum-core-level", 5);
        int configuredPickupLock = loaded.getInt("battery.pickup-lock-core-level", CoreRecord.MAX_LEVEL);
        double configuredCapacityRuneBonus = loaded.getDouble("battery.runes.capacity-bonus-per-tier", 0.10);
        double configuredOfflineRuneBonus = loaded.getDouble("battery.runes.offline-bonus-per-tier", 0.10);
        double configuredOfflineEfficiency = loaded.getDouble("battery.offline.efficiency", OFFLINE_EFFICIENCY);
        long configuredOfflineUpdateSeconds = loaded.getLong("battery.offline.update-seconds", OFFLINE_UPDATE_SECONDS);
        int configuredBaseOfflineHours = loaded.getInt("battery.offline.base-hours", 3);
        int configuredMaximumOfflineHours = loaded.getInt("battery.offline.maximum-hours", 8);
        if (configuredMaterial != Material.COPPER_BLOCK
                || !Double.isFinite(configuredCost) || configuredCost <= 0
                || !Double.isFinite(configuredMinimumDistance) || configuredMinimumDistance < 0
                || !Double.isFinite(configuredMaximumDistance)
                || configuredMaximumDistance <= configuredMinimumDistance
                || configuredMinimumCoreLevel < 1 || configuredMinimumCoreLevel > CoreRecord.MAX_LEVEL
                || configuredPickupLock < 1 || configuredPickupLock > CoreRecord.MAX_LEVEL
                || !Double.isFinite(configuredCapacityRuneBonus) || configuredCapacityRuneBonus < 0
                || !Double.isFinite(configuredOfflineRuneBonus) || configuredOfflineRuneBonus < 0
                || !Double.isFinite(configuredOfflineEfficiency)
                || configuredOfflineEfficiency < 0 || configuredOfflineEfficiency > 1
                || configuredOfflineUpdateSeconds < 1
                || configuredBaseOfflineHours < 1 || configuredBaseOfflineHours > 8
                || configuredMaximumOfflineHours < configuredBaseOfflineHours
                || configuredMaximumOfflineHours > 8) {
            throw new IllegalArgumentException("core/battery.yml contains invalid battery settings.");
        }
        for (int count = 1; count <= MAX_TIER; count++) {
            int level = loaded.getInt("battery.capacity-levels." + count, 5 + (count - 1) * 5);
            if (level < 5 || level > CoreRecord.MAX_LEVEL
                    || (count > 1 && level <= loaded.getInt("battery.capacity-levels." + (count - 1)))) {
                throw new IllegalArgumentException("core/battery.yml capacity-levels must increase from level 5.");
            }
        }
        for (int tier = 2; tier <= MAX_TIER; tier++) {
            int requiredLevel = loaded.getInt("battery.tier-required-core-levels." + tier, tier == 2 ? 8 : 12);
            double cost = loaded.getDouble("battery.tier-upgrade-costs." + tier, tier == 2 ? 2500 : 7500);
            if (requiredLevel < 5 || requiredLevel > CoreRecord.MAX_LEVEL
                    || !Double.isFinite(cost) || cost <= 0) {
                throw new IllegalArgumentException("core/battery.yml tier upgrades contain invalid settings.");
            }
        }
        config = loaded;
        itemMaterial = configuredMaterial;
        purchaseCost = configuredCost;
        minDistance = configuredMinimumDistance;
        maxDistance = configuredMaximumDistance;
        minimumCoreLevel = configuredMinimumCoreLevel;
        pickupLockCoreLevel = configuredPickupLock;
        capacityRuneBonusPerTier = configuredCapacityRuneBonus;
        offlineRuneBonusPerTier = configuredOfflineRuneBonus;
        offlineEfficiency = configuredOfflineEfficiency;
        offlineUpdateSeconds = configuredOfflineUpdateSeconds;
        baseOfflineHours = configuredBaseOfflineHours;
        maximumOfflineHours = configuredMaximumOfflineHours;
    }

    public void reloadConfiguration() {
        applyConfiguration(loadConfiguration(false));
        repository.all().forEach(this::refreshVisuals);
        scheduleOfflineTask();
    }

    private void scheduleOfflineTask() {
        if (offlineTask != null) {
            offlineTask.cancel();
        }
        offlineTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin, task -> processOfflineGeneration(), offlineUpdateSeconds, offlineUpdateSeconds,
                TimeUnit.SECONDS);
    }

    public Listener menuListener() {
        return menu;
    }

    public boolean isBatteryItem(ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    public boolean isBatteryEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(entityKey, PersistentDataType.BYTE);
    }

    public boolean buy(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "battery-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < minimumCoreLevel) {
            messages.send(player, "battery-level-required", Map.of("level", Integer.toString(minimumCoreLevel)));
            return false;
        }
        CoreRecord charged = coreRepository.spendEnergy(islandIndex, purchaseCost);
        if (charged == null) {
            messages.send(player, "battery-insufficient-energy");
            return false;
        }
        try {
            deliverItem(player, createItem(islandIndex, 1, 0, Map.of()));
        } catch (RuntimeException exception) {
            coreRepository.restoreEnergy(islandIndex, purchaseCost);
            plugin.getLogger().log(Level.SEVERE, "Could not deliver a purchased resonance battery.", exception);
            messages.send(player, "battery-purchase-failed");
            return false;
        }
        coreService.refreshCore(charged, System.currentTimeMillis());
        messages.send(player, "battery-purchased", Map.of("cost", format(purchaseCost)));
        return true;
    }

    private void deliverItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            dropped.setPersistent(true);
        }
    }

    private ItemStack createItem(long islandIndex, int tier, double energy, Map<Integer, CellRecord> runes) {
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.item-name"));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("battery-ui.item-level", Map.of(
                        "level", Integer.toString(tier),
                        "max_level", Integer.toString(MAX_TIER)))),
                messages.parseComponent(messages.formatText("battery-ui.item-energy", Map.of(
                        "energy", format(energy)))),
                messages.parseComponent(messages.formatText("battery-ui.item-placement", Map.of(
                        "min", format(minDistance),
                        "max", format(maxDistance)))),
                messages.component("battery-ui.item-place"),
                messages.component("battery-ui.pickup")));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(itemIslandKey, PersistentDataType.LONG, islandIndex);
        meta.getPersistentDataContainer().set(itemTierKey, PersistentDataType.INTEGER, tier);
        meta.getPersistentDataContainer().set(itemEnergyKey, PersistentDataType.DOUBLE, energy);
        runes.forEach((slot, rune) -> {
            if (slot >= 0 && slot < MAX_RUNES) {
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

    public boolean place(Player player, Block clickedBlock) {
        if (!clickedBlock.getType().isSolid()
                || !clickedBlock.getRelative(BlockFace.UP).getType().isAir()
                || !clickedBlock.getRelative(BlockFace.UP, 2).getType().isAir()) {
            messages.send(player, "battery-needs-clearance");
            return false;
        }
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (!isBatteryItem(heldItem)) {
            return false;
        }
        IslandData island = islandService.islandAt(clickedBlock.getLocation()).orElse(null);
        if (island == null || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "battery-access-denied");
            return false;
        }
        Long boundIsland = heldItem.getItemMeta().getPersistentDataContainer()
                .get(itemIslandKey, PersistentDataType.LONG);
        if (boundIsland == null || boundIsland != island.index()) {
            messages.send(player, "battery-wrong-island");
            return false;
        }
        CoreRecord core = coreRepository.core(island.index());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < minimumCoreLevel) {
            messages.send(player, "battery-level-required", Map.of(
                    "level", Integer.toString(minimumCoreLevel)));
            return false;
        }
        int capacity = maxBatteriesForLevel(core.level());
        List<BatteryRecord> existing = repository.forIsland(island.index());
        if (existing.size() >= capacity) {
            messages.send(player, "battery-limit-reached", Map.of(
                    "placed", Integer.toString(existing.size()),
                    "capacity", Integer.toString(capacity)));
            return false;
        }
        Map<Integer, CellRecord> runes = readRunes(heldItem.getItemMeta());
        Integer tier = heldItem.getItemMeta().getPersistentDataContainer()
                .get(itemTierKey, PersistentDataType.INTEGER);
        Double energy = heldItem.getItemMeta().getPersistentDataContainer()
                .get(itemEnergyKey, PersistentDataType.DOUBLE);
        if (runes == null || tier == null || tier < 1 || tier > MAX_TIER
                || energy == null || !Double.isFinite(energy) || energy < 0) {
            messages.send(player, "battery-invalid-item");
            return false;
        }
        int maximumRuneTier = CellRecord.maximumTierForCoreLevel(core.level());
        if (runes.entrySet().stream().anyMatch(entry ->
                entry.getKey() >= tier || entry.getValue().tier() > maximumRuneTier)) {
            messages.send(player, "battery-invalid-item");
            return false;
        }
        Location batteryLocation = new Location(clickedBlock.getWorld(),
                clickedBlock.getX() + 0.5, clickedBlock.getY() + 1.2, clickedBlock.getZ() + 0.5);
        Location coreLocation = new Location(clickedBlock.getWorld(), core.x(), core.y(), core.z());
        double distance = batteryLocation.distance(coreLocation);
        if (distance < minDistance || distance > maxDistance) {
            messages.send(player, "battery-distance-invalid", Map.of(
                    "min", format(minDistance),
                    "max", format(maxDistance)));
            return false;
        }
        UUID batteryId = UUID.randomUUID();
        ItemDisplay display = null;
        Interaction interaction = null;
        TextDisplay textDisplay = null;
        BatteryRecord battery = null;
        try {
            display = clickedBlock.getWorld().spawn(batteryLocation, ItemDisplay.class,
                    spawned -> configureDisplay(spawned, island.index(), batteryId));
            Location interactionLocation = batteryLocation.clone().subtract(0, 0.3, 0);
            interaction = clickedBlock.getWorld().spawn(interactionLocation, Interaction.class, spawned -> {
                spawned.setInteractionWidth(1.0f);
                spawned.setInteractionHeight(1.2f);
                spawned.setResponsive(true);
                spawned.setGravity(false);
                spawned.setInvulnerable(true);
                spawned.setPersistent(true);
                markEntity(spawned, batteryId);
            });
            textDisplay = clickedBlock.getWorld().spawn(
                    batteryLocation.clone().add(0, 1.2, 0), TextDisplay.class, spawned -> {
                        spawned.setBillboard(Display.Billboard.CENTER);
                        spawned.setGravity(false);
                        spawned.setPersistent(true);
                        markEntity(spawned, batteryId);
                    });
            battery = new BatteryRecord(
                    island.index(), batteryId, clickedBlock.getWorld().getUID(),
                    batteryLocation.getX(), batteryLocation.getY(), batteryLocation.getZ(),
                    display.getUniqueId(), interaction.getUniqueId(), textDisplay.getUniqueId(),
                    tier, energy, runes, 0, 0);
            repository.add(battery);
            updateVisuals(battery, clickedBlock.getWorld());
            startAnimation(display, batteryId);
        } catch (RuntimeException exception) {
            if (battery != null) {
                repository.remove(batteryId);
            }
            removeIfPresent(textDisplay);
            removeIfPresent(interaction);
            removeIfPresent(display);
            plugin.getLogger().log(Level.SEVERE, "Could not place a resonance battery.", exception);
            messages.send(player, "battery-place-failed");
            return false;
        }
        if (heldItem.getAmount() == 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            heldItem.setAmount(heldItem.getAmount() - 1);
        }
        messages.send(player, "battery-placed");
        return true;
    }

    private Map<Integer, CellRecord> readRunes(ItemMeta meta) {
        Map<Integer, CellRecord> runes = new HashMap<>();
        for (int slot = 0; slot < MAX_RUNES; slot++) {
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
                if (!rune.type().isBatteryRune()) {
                    return null;
                }
                runes.put(slot, rune);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
        return Map.copyOf(runes);
    }

    public void onEntityLoad(Entity entity) {
        if (!isBatteryEntity(entity)) {
            return;
        }
        BatteryRecord battery = batteryFrom(entity);
        if (battery == null) {
            removeIfPresent(entity);
            return;
        }
        if (entity instanceof ItemDisplay display) {
            configureDisplay(display, battery.islandIndex(), battery.batteryId());
            startAnimation(display, battery.batteryId());
        } else if (entity instanceof Interaction) {
            refreshVisuals(battery);
        }
    }

    public void initializeLoadedEntities() {
        for (BatteryRecord battery : repository.all()) {
            World world = plugin.getServer().getWorld(battery.worldId());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, battery.x(), battery.y(), battery.z());
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                Entity interaction = world.getEntity(battery.interactionId());
                if (interaction instanceof Interaction) {
                    refreshVisuals(battery);
                }
                Entity display = world.getEntity(battery.itemDisplayId());
                if (display instanceof ItemDisplay itemDisplay) {
                    startAnimation(itemDisplay, battery.batteryId());
                }
            });
        }
    }

    public void onPlayerJoin(Player player) {
        onlinePlayers.add(player.getUniqueId());
        if (!rangeGuidePlayers.add(player.getUniqueId())) {
            return;
        }
        if (player.getScheduler().runAtFixedRate(
                        plugin,
                        task -> {
                            if (player.isOnline()
                                    && isBatteryItem(player.getInventory().getItemInMainHand())) {
                                pylons.showPlacementRange(player, minDistance, maxDistance);
                            }
                        },
                        () -> rangeGuidePlayers.remove(player.getUniqueId()),
                        1L,
                        RANGE_GUIDE_PERIOD_TICKS)
                == null) {
            rangeGuidePlayers.remove(player.getUniqueId());
        }
    }

    public void onPlayerQuit(Player player) {
        onlinePlayers.remove(player.getUniqueId());
    }

    private void processOfflineGeneration() {
        long now = System.currentTimeMillis();
        Set<UUID> onlineSnapshot = Set.copyOf(onlinePlayers);
        for (IslandData island : islandService.allIslands()) {
            CoreRecord core = coreRepository.core(island.index());
            if (core == null) {
                continue;
            }
            World world = plugin.getServer().getWorld(core.worldId());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, core.x(), core.y(), core.z());
            try {
                plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                    try {
                        processOfflineGeneration(island, onlineSnapshot, now);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not process offline battery generation for island "
                                        + island.index() + ".",
                                exception);
                    }
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not schedule offline battery generation for island "
                                + island.index() + ".",
                        exception);
            }
        }
    }

    private void processOfflineGeneration(IslandData island, Set<UUID> onlineSnapshot, long now) {
        List<BatteryRecord> islandBatteries = repository.forIsland(island.index());
        if (islandBatteries.isEmpty()) {
            return;
        }
        boolean islandOnline = onlineSnapshot.contains(island.owner())
                || island.members().keySet().stream().anyMatch(onlineSnapshot::contains);
        CoreRecord core = coreRepository.core(island.index());
        if (core == null) {
            return;
        }
        if (islandOnline) {
            for (BatteryRecord battery : islandBatteries) {
                if (battery.offlineMillis() != 0 || battery.lastOfflineCheck() != 0) {
                    repository.update(battery.withOfflineProgress(battery.energy(), 0, 0));
                }
            }
            return;
        }
        double pylonRate = pylons.offlineYieldPerSecond(island.index());
        if (pylonRate <= 0) {
            for (BatteryRecord battery : islandBatteries) {
                if (battery.lastOfflineCheck() != now) {
                    repository.update(battery.withOfflineProgress(
                            battery.energy(), battery.offlineMillis(), now));
                }
            }
            return;
        }
        long maximumOfflineMillis = maximumOfflineHours(core) * 3_600_000L;
        double perBatteryBaseRate = pylonRate / islandBatteries.size() * offlineEfficiency;
        for (BatteryRecord battery : islandBatteries) {
            long previousCheck = battery.lastOfflineCheck() == 0 ? now : battery.lastOfflineCheck();
            long elapsed = Math.max(0, now - previousCheck);
            long remaining = Math.max(0, maximumOfflineMillis - battery.offlineMillis());
            long collectedMillis = Math.min(elapsed, remaining);
            double requested = perBatteryBaseRate * (collectedMillis / 1000.0)
                    * battery.offlineMultiplier(offlineRuneBonusPerTier);
            double capacity = battery.capacity(core, capacityRuneBonusPerTier);
            double stored = Math.min(requested, Math.max(0, capacity - battery.energy()));
            long newOfflineMillis = Math.min(maximumOfflineMillis, battery.offlineMillis() + elapsed);
            BatteryRecord updated = battery.withOfflineProgress(
                    battery.energy() + stored, newOfflineMillis, now);
            if (updated.energy() != battery.energy()
                    || updated.offlineMillis() != battery.offlineMillis()
                    || updated.lastOfflineCheck() != battery.lastOfflineCheck()) {
                repository.update(updated);
                if (stored > 0) {
                    coreRepository.recordGeneratedEnergy(island.index(), stored);
                }
                refreshVisuals(updated);
            }
        }
    }

    public double storeOverflow(long islandIndex, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return 0;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            return 0;
        }
        double remaining = amount;
        double storedTotal = 0;
        for (BatteryRecord battery : repository.forIsland(islandIndex)) {
            double capacity = battery.capacity(core, capacityRuneBonusPerTier);
            double stored = Math.min(remaining, Math.max(0, capacity - battery.energy()));
            if (stored <= 0) {
                continue;
            }
            BatteryRecord updated = battery.withEnergy(battery.energy() + stored);
            repository.update(updated);
            storedTotal += stored;
            remaining -= stored;
            refreshVisuals(updated);
            if (remaining <= 0) {
                break;
            }
        }
        return storedTotal;
    }

    public double withdrawForCore(long islandIndex, double requestedAmount) {
        if (!Double.isFinite(requestedAmount) || requestedAmount <= 0) {
            return 0;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            return 0;
        }
        double remaining = Math.min(requestedAmount, core.maxEnergy() - core.energy());
        double withdrawnTotal = 0;
        for (BatteryRecord battery : repository.forIsland(islandIndex)) {
            double withdrawn = Math.min(remaining, battery.energy());
            if (withdrawn <= 0) {
                continue;
            }
            BatteryRecord updated = battery.withEnergy(battery.energy() - withdrawn);
            repository.update(updated);
            withdrawnTotal += withdrawn;
            remaining -= withdrawn;
            refreshVisuals(updated);
            if (remaining <= 0) {
                break;
            }
        }
        return withdrawnTotal;
    }

    public boolean transferEnergyToCore(Player player, UUID batteryId) {
        BatteryRecord battery = repository.get(batteryId);
        if (battery == null || !canManage(player, battery)) {
            return false;
        }
        CoreRecord core = coreRepository.core(battery.islandIndex());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        double availableSpace = Math.max(0, core.maxEnergy() - core.energy());
        if (availableSpace <= 0) {
            messages.send(player, "battery-core-full");
            return false;
        }
        if (battery.energy() <= 0) {
            messages.send(player, "battery-empty");
            return false;
        }
        double withdrawn = withdrawForCore(
                battery.islandIndex(), Math.min(availableSpace, battery.energy()));
        double transferred = coreRepository.restoreEnergy(battery.islandIndex(), withdrawn);
        if (transferred < withdrawn) {
            storeOverflow(battery.islandIndex(), withdrawn - transferred);
        }
        if (transferred <= 0) {
            messages.send(player, "battery-core-full");
            return false;
        }
        CoreRecord updatedCore = coreRepository.core(battery.islandIndex());
        if (updatedCore != null) {
            coreService.refreshCore(updatedCore, System.currentTimeMillis());
        }
        messages.send(player, "battery-energy-transferred", Map.of(
                "energy", format(transferred)));
        return true;
    }

    private void refreshVisuals(BatteryRecord battery) {
        World world = plugin.getServer().getWorld(battery.worldId());
        if (world == null) {
            return;
        }
        Location location = new Location(world, battery.x(), battery.y(), battery.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> updateVisuals(battery, world));
    }

    private void updateVisuals(BatteryRecord battery, World world) {
        CoreRecord core = coreRepository.core(battery.islandIndex());
        if (core == null) {
            return;
        }
        Entity textEntity = world.getEntity(battery.textDisplayId());
        if (textEntity instanceof TextDisplay display) {
            display.text(batteryHologram(battery, core));
        }
        Entity itemEntity = world.getEntity(battery.itemDisplayId());
        if (itemEntity instanceof ItemDisplay display) {
            display.setItemStack(new ItemStack(Material.COPPER_BLOCK));
        }
    }

    private Component batteryHologram(BatteryRecord battery, CoreRecord core) {
        return messages.parseComponent(messages.formatText("battery-ui.hologram-name", Map.of(
                "energy", format(battery.energy()),
                "capacity", format(capacity(battery, core)),
                "level", Integer.toString(battery.tier()))));
    }

    private void configureDisplay(ItemDisplay display, long islandIndex, UUID batteryId) {
        display.setItemStack(new ItemStack(Material.COPPER_BLOCK));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(0.9f), new Quaternionf()));
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(true);
        markEntity(display, batteryId);
    }

    private void markEntity(Entity entity, UUID batteryId) {
        entity.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(entityIdKey, PersistentDataType.STRING, batteryId.toString());
    }

    private BatteryRecord batteryFrom(Entity entity) {
        String id = entity.getPersistentDataContainer().get(entityIdKey, PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return repository.get(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void startAnimation(ItemDisplay display, UUID batteryId) {
        if (animationTasks.containsKey(batteryId)) {
            return;
        }
        float[] yaw = {0};
        ScheduledTask[] taskReference = new ScheduledTask[1];
        ScheduledTask task = display.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!display.isValid() || repository.get(batteryId) == null) {
                scheduled.cancel();
                animationTasks.remove(batteryId, taskReference[0]);
                return;
            }
            yaw[0] = (yaw[0] + 0.08f) % ((float) (Math.PI * 2));
            display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf().rotationY(yaw[0]),
                    new Vector3f(0.9f),
                    new Quaternionf()));
        }, () -> animationTasks.remove(batteryId, taskReference[0]), 1L, 2L);
        if (task != null) {
            taskReference[0] = task;
            animationTasks.put(batteryId, task);
        }
    }

    public void openMenu(Player player, Entity entity) {
        BatteryRecord battery = batteryFrom(entity);
        if (battery != null && canManage(player, battery)) {
            menu.open(player, battery);
        }
    }

    public boolean pickup(Player player, Entity entity) {
        BatteryRecord battery = batteryFrom(entity);
        if (battery == null) {
            return false;
        }
        if (!canManage(player, battery)) {
            return false;
        }
        CoreRecord core = coreRepository.core(battery.islandIndex());
        if (core != null && core.level() >= pickupLockCoreLevel) {
            messages.send(player, "battery-pickup-locked");
            return false;
        }
        BatteryRecord removed = repository.remove(battery.batteryId());
        if (removed == null) {
            return false;
        }
        try {
            deliverItem(player, createItem(
                    removed.islandIndex(), removed.tier(), removed.energy(), removed.runes()));
        } catch (RuntimeException exception) {
            repository.restore(removed);
            plugin.getLogger().log(Level.SEVERE, "Could not return a picked-up resonance battery.", exception);
            messages.send(player, "battery-pickup-failed");
            return false;
        }
        cancelTasks(removed.batteryId());
        removeBatteryEntities(removed);
        messages.send(player, "battery-picked-up");
        return true;
    }

    public boolean canManage(Player player, BatteryRecord battery) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != battery.islandIndex()
                || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "battery-access-denied");
            return false;
        }
        return true;
    }

    public BatteryRecord record(UUID batteryId) {
        return repository.get(batteryId);
    }

    public CoreRecord core(long islandIndex) {
        return coreRepository.core(islandIndex);
    }

    public double capacity(BatteryRecord battery, CoreRecord core) {
        return battery.capacity(core, capacityRuneBonusPerTier);
    }

    public double totalEnergy(long islandIndex) {
        return repository.forIsland(islandIndex).stream()
                .mapToDouble(BatteryRecord::energy)
                .sum();
    }

    public double totalCapacity(long islandIndex) {
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            return 0;
        }
        return repository.forIsland(islandIndex).stream()
                .mapToDouble(battery -> capacity(battery, core))
                .sum();
    }

    public double transferableEnergyToCore(long islandIndex) {
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            return 0;
        }
        double coreSpace = Math.max(0, core.maxEnergy() - core.energy());
        return Math.min(coreSpace, totalEnergy(islandIndex));
    }

    public int placedBatteryCount(long islandIndex) {
        return repository.forIsland(islandIndex).size();
    }

    public double purchaseCost() {
        return purchaseCost;
    }

    public int minimumCoreLevel() {
        return minimumCoreLevel;
    }

    public int baseIntervalTicks() {
        return pylons.baseIntervalTicks();
    }

    public boolean upgrade(Player player, UUID batteryId) {
        BatteryRecord battery = repository.get(batteryId);
        if (battery == null || !canManage(player, battery)) {
            return false;
        }
        if (battery.tier() >= MAX_TIER) {
            messages.send(player, "battery-max-tier");
            return false;
        }
        int nextTier = battery.tier() + 1;
        int requiredLevel = upgradeRequiredLevel(nextTier);
        CoreRecord core = coreRepository.core(battery.islandIndex());
        if (core == null || core.level() < requiredLevel) {
            messages.send(player, "battery-tier-level-required", Map.of(
                    "level", Integer.toString(requiredLevel)));
            return false;
        }
        double cost = upgradeCost(nextTier);
        CoreRecord charged = coreRepository.spendEnergy(battery.islandIndex(), cost);
        if (charged == null) {
            messages.send(player, "battery-insufficient-energy");
            return false;
        }
        BatteryRecord updated = battery.withTier(nextTier);
        repository.update(updated);
        coreService.refreshCore(charged, System.currentTimeMillis());
        refreshVisuals(updated);
        messages.send(player, "battery-upgraded", Map.of(
                "tier", Integer.toString(nextTier),
                "cost", format(cost)));
        return true;
    }

    public boolean installFirstAvailableRune(Player player, UUID batteryId, int inventorySlot, CellRecord rune) {
        BatteryRecord battery = repository.get(batteryId);
        if (battery == null || !canManage(player, battery)) {
            return false;
        }
        if (!rune.type().isBatteryRune()) {
            return false;
        }
        CoreRecord core = coreRepository.core(battery.islandIndex());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        int requiredTier = CellRecord.maximumTierForCoreLevel(core.level());
        if (rune.tier() > requiredTier) {
            messages.send(player, "battery-rune-tier-locked", Map.of("tier", Integer.toString(requiredTier)));
            return false;
        }
        int slot = -1;
        for (int i = 0; i < battery.tier(); i++) {
            if (!battery.runes().containsKey(i)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            messages.send(player, "battery-rune-slots-full");
            return false;
        }
        ItemStack item = player.getInventory().getItem(inventorySlot);
        if (item == null || item.getAmount() != 1 || !rune.equals(CellItem.read(item))) {
            return false;
        }
        repository.update(battery.withRune(slot, rune));
        player.getInventory().setItem(inventorySlot, null);
        refreshVisuals(battery);
        return true;
    }

    public boolean removeRune(Player player, UUID batteryId, int slot) {
        BatteryRecord battery = repository.get(batteryId);
        if (battery == null || !canManage(player, battery)) {
            return false;
        }
        CoreRecord core = coreRepository.core(battery.islandIndex());
        if (core != null && core.level() >= pickupLockCoreLevel) {
            messages.send(player, "battery-pickup-locked");
            return false;
        }
        CellRecord rune = battery.runes().get(slot);
        if (rune == null) {
            return false;
        }
        repository.update(battery.withRune(slot, null));
        try {
            deliverItem(player, CellItem.create(rune, pylons.baseIntervalTicks(), messages));
        } catch (RuntimeException exception) {
            repository.restore(battery);
            plugin.getLogger().log(Level.SEVERE, "Could not return a battery rune.", exception);
            messages.send(player, "battery-pickup-failed");
            return false;
        }
        refreshVisuals(battery);
        return true;
    }

    public boolean canFuseRuneTier(Player player, int tier) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        CoreRecord core = island == null ? null : coreRepository.core(island.index());
        int maximumTier = core == null ? 0 : CellRecord.maximumTierForCoreLevel(core.level());
        if (tier > maximumTier) {
            messages.send(player, "battery-rune-tier-locked", Map.of(
                    "tier", Integer.toString(maximumTier)));
            return false;
        }
        return true;
    }

    public double upgradeCost(int tier) {
        return config.getDouble("battery.tier-upgrade-costs." + tier, Double.NaN);
    }

    public int upgradeRequiredLevel(int tier) {
        return config.getInt("battery.tier-required-core-levels." + tier, Integer.MAX_VALUE);
    }

    public int maxBatteriesForLevel(int coreLevel) {
        int capacity = 0;
        for (int count = 1; count <= MAX_TIER; count++) {
            if (coreLevel >= config.getInt("battery.capacity-levels." + count, 5 + (count - 1) * 5)) {
                capacity = count;
            }
        }
        return capacity;
    }

    public int maximumOfflineHours(long islandIndex) {
        CoreRecord core = coreRepository.core(islandIndex);
        return core == null ? baseOfflineHours : maximumOfflineHours(core);
    }

    private int maximumOfflineHours(CoreRecord core) {
        return Math.min(maximumOfflineHours, baseOfflineHours + core.upgradeOfflineHours());
    }

    public boolean destroyForFracture(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-fracture-owner-only");
            return false;
        }
        for (BatteryRecord battery : repository.removeIsland(islandIndex)) {
            cancelTasks(battery.batteryId());
            removeBatteryEntities(battery);
        }
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (island.canBuild(online.getUniqueId())) {
                online.getScheduler().run(plugin, task -> removeBoundItems(online, islandIndex), null);
            }
        }
        return true;
    }

    private void removeBoundItems(Player player, long islandIndex) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isBatteryItem(item)) {
                continue;
            }
            Long boundIsland = item.getItemMeta().getPersistentDataContainer()
                    .get(itemIslandKey, PersistentDataType.LONG);
            if (boundIsland != null && boundIsland == islandIndex) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void removeBatteryEntities(BatteryRecord battery) {
        ScheduledTask animation = animationTasks.remove(battery.batteryId());
        if (animation != null) {
            animation.cancel();
        }
        World world = plugin.getServer().getWorld(battery.worldId());
        if (world == null) {
            return;
        }
        Location location = new Location(world, battery.x(), battery.y(), battery.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            removeIfPresent(world.getEntity(battery.itemDisplayId()));
            removeIfPresent(world.getEntity(battery.interactionId()));
            removeIfPresent(world.getEntity(battery.textDisplayId()));
        });
    }

    private void cancelTasks(UUID batteryId) {
        ScheduledTask animation = animationTasks.remove(batteryId);
        if (animation != null) {
            animation.cancel();
        }
    }

    public void shutdown() throws IOException {
        if (offlineTask != null) {
            offlineTask.cancel();
        }
        rangeGuidePlayers.clear();
        animationTasks.values().forEach(ScheduledTask::cancel);
        animationTasks.clear();
        repository.flush();
    }

    private void removeIfPresent(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private String format(double value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000);
        }
        if (value >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", value / 1_000);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
