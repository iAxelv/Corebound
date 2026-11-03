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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class PylonService {
    private static final int TIER_ONE = 1;
    private static final int RANGE_GUIDE_POINTS = 72;
    private static final long RANGE_GUIDE_PERIOD_TICKS = 12L;

    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final CoreService coreService;
    private final CoreRepository coreRepository;
    private final PylonRepository repository;
    private final Messages messages;
    private final YamlConfiguration config;
    private final NamespacedKey pylonItemKey;
    private final NamespacedKey pylonTierKey;
    private final NamespacedKey pylonCellsKey;
    private final NamespacedKey pylonIslandKey;
    private final NamespacedKey pylonEntityKey;
    private final NamespacedKey pylonIdKey;
    private final Material itemMaterial;
    private final Material baseMaterial;
    private final Material rodMaterial;
    private final float displayScale;
    private final int intervalTicks;
    private final double minimumDistance;
    private final double maximumDistance;
    private final double baseYieldRatio;
    private final double purchaseCost;
    private final double forgeRefreshCost;
    private final Map<CellType, Double> cellForgeCosts = new java.util.EnumMap<>(CellType.class);
    private final Map<Integer, Integer> tierCoreLevelRequirements = Map.of(2, 5, 3, 10);
    private final Map<Long, CellType[]> cellForgeOffers = new ConcurrentHashMap<>();
    private final Map<Long, boolean[]> availableCellForgeOffers = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> activePylons = new ConcurrentHashMap<>();
    private final Set<UUID> rangeGuidePlayers = ConcurrentHashMap.newKeySet();
    private final PylonMenu pylonMenu;

    public PylonService(
            TheCorePlugin plugin,
            IslandService islandService,
            CoreService coreService,
            CoreRepository coreRepository,
            PylonRepository repository,
            Messages messages,
            File configFile) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.coreService = coreService;
        this.coreRepository = coreRepository;
        this.repository = repository;
        this.messages = messages;
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultsStream = plugin.getResource("core/pylons.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/pylons.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not migrate core/pylons.yml defaults.", exception);
        }

        itemMaterial = configuredMaterial("pylon.item.material", "SEA_LANTERN", true);
        baseMaterial = configuredMaterial("pylon.display.base-material", "SEA_LANTERN", false);
        rodMaterial = configuredMaterial("pylon.display.material", "END_ROD", false);
        double configuredScale = config.getDouble("pylon.display.scale", 1.2);
        if (!Double.isFinite(configuredScale) || configuredScale <= 0 || configuredScale > Float.MAX_VALUE) {
            throw new IllegalArgumentException("pylons.yml pylon.display.scale must be a positive finite number.");
        }
        displayScale = (float) configuredScale;
        intervalTicks = config.getInt("pylon.interval-ticks", 60);
        minimumDistance = config.getDouble("pylon.min-distance", 3.0);
        maximumDistance = config.getDouble("pylon.max-distance", 12.0);
        baseYieldRatio = config.getDouble("pylon.base-yield-ratio", 0.4);
        purchaseCost = config.getDouble("pylon.purchase-cost", 150.0);
        forgeRefreshCost = config.getDouble("pylon.cells.refresh-cost", 75.0);
        if (!Double.isFinite(forgeRefreshCost) || forgeRefreshCost < 0) {
            throw new IllegalArgumentException("pylons.yml pylon.cells.refresh-cost must be a non-negative finite number.");
        }
        for (CellType type : CellType.values()) {
            double cost = config.getDouble("pylon.cells.costs." + type.name(), 100.0);
            if (!Double.isFinite(cost) || cost <= 0) {
                throw new IllegalArgumentException("pylons.yml cell forge costs must be positive finite numbers.");
            }
            cellForgeCosts.put(type, cost);
        }
        int tierTwoCoreLevel = pylonUpgradeRequiredCoreLevel(2);
        int tierThreeCoreLevel = pylonUpgradeRequiredCoreLevel(3);
        if (tierTwoCoreLevel < 3 || tierThreeCoreLevel <= tierTwoCoreLevel) {
            throw new IllegalArgumentException(
                    "pylons.yml tier-required-core-levels must be at least 3 and increase for each tier.");
        }
        if (intervalTicks <= 0
                || !Double.isFinite(minimumDistance)
                || !Double.isFinite(maximumDistance)
                || minimumDistance < 0
                || maximumDistance <= minimumDistance
                || !Double.isFinite(baseYieldRatio)
                || baseYieldRatio <= 0
                || !Double.isFinite(purchaseCost)
                || purchaseCost <= 0) {
            throw new IllegalArgumentException("pylons.yml contains invalid distance, interval, yield, or cost.");
        }
        pylonItemKey = new NamespacedKey(plugin, "pylon_item");
        pylonTierKey = new NamespacedKey(plugin, "pylon_tier");
        pylonCellsKey = new NamespacedKey(plugin, "pylon_cells");
        pylonIslandKey = new NamespacedKey(plugin, "pylon_island_index");
        pylonEntityKey = new NamespacedKey(plugin, "pylon_entity");
        pylonIdKey = new NamespacedKey(plugin, "pylon_id");
        pylonMenu = new PylonMenu(messages, this);
    }

    public double purchaseCost() {
        return purchaseCost;
    }

    public PylonMenu menuListener() {
        return pylonMenu;
    }

    public PylonRecord getPylon(UUID pylonId) {
        return repository.get(pylonId);
    }

    public void openPylonMenu(Player player, Entity entity) {
        PylonRecord pylon = pylonFrom(entity);
        if (pylon == null) {
            return;
        }
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != pylon.islandIndex() || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "pylon-place-access-denied");
            return;
        }
        pylonMenu.openPylon(player, pylon);
    }

    public void openCellForge(Player player, long islandIndex) {
        if (canManageIslandCore(player, islandIndex)) {
            CoreRecord core = coreRepository.core(islandIndex);
            if (core == null || core.level() < 3) {
                messages.send(player, "cell-forge-locked");
                return;
            }
            pylonMenu.openForge(player, islandIndex);
        }
    }

    public double cellForgeCost(CellType type) {
        return cellForgeCosts.get(type);
    }

    public double forgeRefreshCost() {
        return forgeRefreshCost;
    }

    public int baseIntervalTicks() {
        return intervalTicks;
    }

    public CellType[] randomCellOffers() {
        CellType[] types = CellType.values();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return new CellType[] {
            types[random.nextInt(types.length)],
            types[random.nextInt(types.length)],
            types[random.nextInt(types.length)]
        };
    }

    public CellType[] cellForgeOffers(long islandIndex) {
        return cellForgeOffers.computeIfAbsent(islandIndex, ignored -> randomCellOffers()).clone();
    }

    public boolean[] availableCellForgeOffers(long islandIndex) {
        cellForgeOffers(islandIndex);
        return availableCellForgeOffers.computeIfAbsent(islandIndex, ignored -> new boolean[] {true, true, true})
                .clone();
    }

    public void consumeCellForgeOffer(long islandIndex, int slotIndex) {
        boolean[] available = availableCellForgeOffers.computeIfAbsent(
                islandIndex, ignored -> new boolean[] {true, true, true});
        if (slotIndex >= 0 && slotIndex < available.length) {
            available[slotIndex] = false;
        }
    }

    public CellType[] refreshCellForge(Player player, long islandIndex) {
        if (!canManageIslandCore(player, islandIndex)) {
            return null;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null || core.level() < 3) {
            messages.send(player, "cell-forge-locked");
            return null;
        }
        CoreRecord charged = coreRepository.spendEnergy(islandIndex, forgeRefreshCost);
        if (charged == null) {
            messages.send(player, "cell-forge-refresh-insufficient-energy");
            return null;
        }
        coreService.refreshCore(charged, System.currentTimeMillis());
        CellType[] refreshed = randomCellOffers();
        cellForgeOffers.put(islandIndex, refreshed);
        availableCellForgeOffers.put(islandIndex, new boolean[] {true, true, true});
        messages.send(player, "cell-forge-refreshed", Map.of("cost", formatEnergy(forgeRefreshCost)));
        return refreshed.clone();
    }

    public boolean forgeCell(Player player, long islandIndex, CellType type) {
        if (!canManageIslandCore(player, islandIndex)) {
            return false;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null || core.level() < 3) {
            messages.send(player, "cell-forge-locked");
            return false;
        }
        double cost = cellForgeCost(type);
        CoreRecord charged = coreRepository.spendEnergy(islandIndex, cost);
        if (charged == null) {
            messages.send(player, "cell-forge-insufficient-energy");
            return false;
        }
        try {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(
                    CellItem.create(new CellRecord(type, 1), intervalTicks));
            for (ItemStack leftover : overflow.values()) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                dropped.setPersistent(true);
            }
        } catch (RuntimeException exception) {
            coreRepository.restoreEnergy(islandIndex, cost);
            plugin.getLogger().log(Level.SEVERE, "Could not deliver a forged elemental cell.", exception);
            messages.send(player, "cell-forge-failed");
            return false;
        }
        coreService.refreshCore(charged, System.currentTimeMillis());
        messages.send(player, "cell-forged", Map.of(
                "cell", type.displayName(),
                "cost", formatEnergy(cost)));
        return true;
    }

    public boolean canManageIslandCore(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "pylon-menu-access-denied");
            return false;
        }
        return true;
    }

    public void setEquippedCell(UUID pylonId, int slot, CellRecord cell) {
        PylonRecord pylon = repository.get(pylonId);
        if (pylon == null) {
            return;
        }
        PylonRecord updated = pylon.withCell(slot, cell);
        repository.update(updated);
        refreshPylon(updated);
        restartPulse(updated);
    }

    public boolean upgradePylon(Player player, UUID pylonId) {
        PylonRecord pylon = repository.get(pylonId);
        if (pylon == null) {
            return false;
        }
        if (!canManageIslandCore(player, pylon.islandIndex())) {
            return false;
        }
        if (pylon.tier() >= 3) {
            messages.send(player, "pylon-max-tier");
            return false;
        }
        int nextTier = pylon.tier() + 1;
        int requiredCoreLevel = pylonUpgradeRequiredCoreLevel(nextTier);
        CoreRecord core = coreRepository.core(pylon.islandIndex());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < requiredCoreLevel) {
            messages.send(player, "pylon-upgrade-requires-core-level", Map.of(
                    "tier", Integer.toString(nextTier),
                    "level", Integer.toString(requiredCoreLevel)));
            return false;
        }
        double cost = config.getDouble("pylon.tier-upgrade-costs." + nextTier, Double.NaN);
        if (!Double.isFinite(cost) || cost <= 0) {
            plugin.getLogger().severe("Missing or invalid pylon tier-upgrade-costs." + nextTier + " in pylons.yml.");
            messages.send(player, "pylon-upgrade-failed");
            return false;
        }
        CoreRecord charged = coreRepository.spendEnergy(pylon.islandIndex(), cost);
        if (charged == null) {
            messages.send(player, "pylon-upgrade-insufficient-energy");
            return false;
        }
        PylonRecord upgraded = pylon.withTier(nextTier);
        repository.update(upgraded);
        coreService.refreshCore(charged, System.currentTimeMillis());
        refreshPylon(upgraded);
        messages.send(player, "pylon-upgraded", Map.of(
                "tier", Integer.toString(nextTier),
                "cost", formatEnergy(cost)));
        return true;
    }

    public double pylonUpgradeCost(int nextTier) {
        return config.getDouble("pylon.tier-upgrade-costs." + nextTier, Double.NaN);
    }

    public int pylonUpgradeRequiredCoreLevel(int nextTier) {
        return config.getInt("pylon.tier-required-core-levels." + nextTier,
                tierCoreLevelRequirements.getOrDefault(nextTier, Integer.MAX_VALUE));
    }

    public int coreLevel(long islandIndex) {
        CoreRecord core = coreRepository.core(islandIndex);
        return core == null ? 0 : core.level();
    }

    public int ownedPylonCount(long islandIndex) {
        return repository.forIsland(islandIndex).size() + repository.pendingItems(islandIndex);
    }

    public boolean hasPylonCapacity(long islandIndex, int capacity) {
        return ownedPylonCount(islandIndex) < capacity;
    }

    public boolean canManagePylon(Player player, PylonRecord pylon) {
        return canManageIslandCore(player, pylon.islandIndex());
    }

    private void refreshPylon(PylonRecord pylon) {
        CoreRecord core = coreRepository.core(pylon.islandIndex());
        World world = plugin.getServer().getWorld(pylon.worldId());
        if (core == null || world == null) {
            return;
        }
        Location location = new Location(world, pylon.x(), pylon.y(), pylon.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> updatePylonVisuals(pylon, core, world));
    }

    public void onPlayerJoin(Player player) {
        if (!rangeGuidePlayers.add(player.getUniqueId())) {
            return;
        }
        if (player.getScheduler().runAtFixedRate(
                        plugin,
                        task -> showRangeGuide(player),
                        () -> rangeGuidePlayers.remove(player.getUniqueId()),
                        1L,
                        RANGE_GUIDE_PERIOD_TICKS)
                == null) {
            rangeGuidePlayers.remove(player.getUniqueId());
        }
    }

    private void removeEntity(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    public void initializeRangeGuides() {
        plugin.getServer().getOnlinePlayers().forEach(this::onPlayerJoin);
    }

    public boolean buy(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "pylon-menu-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < 3) {
            messages.send(player, "pylon-requires-core-level");
            return false;
        }
        if (!repository.reserveItem(islandIndex, core.pylonCapacity())) {
            messages.send(player, "pylon-limit-reached");
            return false;
        }
        CoreRecord chargedCore = coreRepository.spendEnergy(islandIndex, purchaseCost);
        if (chargedCore == null) {
            repository.releasePendingItem(islandIndex);
            messages.send(player, "pylon-insufficient-energy");
            return false;
        }
        ItemStack item = createPylonItem(islandIndex);
        try {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                dropped.setPersistent(true);
            }
        } catch (RuntimeException exception) {
            repository.releasePendingItem(islandIndex);
            coreRepository.restoreEnergy(islandIndex, purchaseCost);
            plugin.getLogger().log(Level.SEVERE, "Could not deliver a forged pylon.", exception);
            messages.send(player, "pylon-purchase-failed");
            return false;
        }
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        messages.send(player, "pylon-purchased", Map.of("cost", formatEnergy(purchaseCost)));
        return true;
    }

    public boolean isPylonItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(pylonItemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public boolean isPylonEntity(org.bukkit.entity.Entity entity) {
        return entity.getPersistentDataContainer().has(pylonEntityKey, PersistentDataType.BYTE);
    }

    public boolean pickupPylon(Player player, Entity entity) {
        String pylonIdValue = entity.getPersistentDataContainer().get(pylonIdKey, PersistentDataType.STRING);
        if (pylonIdValue == null) {
            return false;
        }
        UUID pylonId = UUID.fromString(pylonIdValue);
        PylonRecord pylon = repository.get(pylonId);
        if (pylon == null) {
            return false;
        }
        IslandData island = islandService.islandAt(entity.getLocation()).orElse(null);
        if (island == null || island.index() != pylon.islandIndex() || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "pylon-place-access-denied");
            return false;
        }
        PylonRecord removed = repository.removeAndReserveItem(pylonId);
        if (removed == null) {
            return false;
        }
        stopPulse(pylonId);
        World world = plugin.getServer().getWorld(removed.worldId());
        if (world != null) {
            removeEntity(world.getEntity(removed.itemDisplayId()));
            removeEntity(world.getEntity(removed.rodDisplayId()));
            removeEntity(world.getEntity(removed.interactionId()));
            if (removed.textDisplayId() != null) {
                removeEntity(world.getEntity(removed.textDisplayId()));
            }
        }
        ItemStack item = createPylonItem(removed);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            dropped.setPersistent(true);
        }
        messages.send(player, "pylon-picked-up");
        return true;
    }

    public boolean preservePylons(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-fracture-owner-only");
            return false;
        }
        int returned = 0;
        for (PylonRecord pylon : repository.forIsland(islandIndex)) {
            PylonRecord removed = repository.removeAndReserveItem(pylon.pylonId());
            if (removed == null) {
                continue;
            }
            stopPulse(removed.pylonId());
            schedulePylonEntityRemoval(removed);
            ItemStack item = createPylonItem(removed);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                dropped.setPersistent(true);
            }
            returned++;
        }
        if (returned > 0) {
            messages.send(player, "core-fracture-pylons-returned", Map.of(
                    "count", Integer.toString(returned)));
        }
        return true;
    }

    private void schedulePylonEntityRemoval(PylonRecord pylon) {
        World world = plugin.getServer().getWorld(pylon.worldId());
        if (world == null) {
            return;
        }
        Location location = new Location(world, pylon.x(), pylon.y(), pylon.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            removeEntity(world.getEntity(pylon.itemDisplayId()));
            removeEntity(world.getEntity(pylon.rodDisplayId()));
            removeEntity(world.getEntity(pylon.interactionId()));
            if (pylon.textDisplayId() != null) {
                removeEntity(world.getEntity(pylon.textDisplayId()));
            }
        });
    }

    public boolean place(Player player, org.bukkit.block.Block clickedBlock) {
        if (!clickedBlock.getType().isSolid()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP).getType().isAir()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP, 2).getType().isAir()) {
            messages.send(player, "pylon-needs-clearance");
            return false;
        }
        IslandData island = islandService.islandAt(clickedBlock.getLocation()).orElse(null);
        if (island == null || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "pylon-place-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(island.index());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < 3) {
            messages.send(player, "pylon-requires-core-level");
            return false;
        }
        ItemStack heldPylon = player.getInventory().getItemInMainHand();
        Long reservedIsland = heldPylon.getItemMeta().getPersistentDataContainer()
                .get(pylonIslandKey, PersistentDataType.LONG);
        if (reservedIsland != null && reservedIsland != island.index()) {
            messages.send(player, "pylon-item-wrong-island");
            return false;
        }
        if (!repository.canPlaceItem(island.index(), core.pylonCapacity(), reservedIsland != null)) {
            messages.send(player, "pylon-limit-reached");
            return false;
        }

        Location pylonLocation = new Location(
                clickedBlock.getWorld(),
                clickedBlock.getX() + 0.5,
                clickedBlock.getY() + 1.2,
                clickedBlock.getZ() + 0.5);
        Location coreLocation = new Location(
                clickedBlock.getWorld(), core.x(), core.y(), core.z());
        double distance = pylonLocation.distance(coreLocation);
        if (distance < minimumDistance || distance > maximumDistance) {
            messages.send(player, "pylon-distance-invalid", Map.of(
                    "min", formatEnergy(minimumDistance),
                    "max", formatEnergy(maximumDistance)));
            return false;
        }

        UUID pylonId = UUID.randomUUID();
        int pylonTier = pylonTier(heldPylon);
        Map<Integer, CellRecord> equippedCells = pylonCells(heldPylon);
        ItemDisplay baseDisplay = null;
        ItemDisplay rodDisplay = null;
        Interaction interaction = null;
        TextDisplay textDisplay = null;
        PylonRecord placedPylon = null;
        try {
            baseDisplay = clickedBlock.getWorld().spawn(pylonLocation, ItemDisplay.class, display -> {
                configureDisplay(display, pylonBaseMaterial(equippedCells), 0.75f, island.index(), pylonId);
            });
            Location rodLocation = pylonLocation.clone().add(0, 0.85, 0);
            rodDisplay = clickedBlock.getWorld().spawn(rodLocation, ItemDisplay.class, display -> {
                configureDisplay(display, rodMaterial, displayScale, island.index(), pylonId);
            });
            Location interactionLocation = pylonLocation.clone().subtract(0, 0.6, 0);
            interaction = clickedBlock.getWorld().spawn(interactionLocation, Interaction.class, entity -> {
                entity.setInteractionWidth(1.0f);
                entity.setInteractionHeight(1.5f);
                entity.setResponsive(true);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(true);
                markEntity(entity, island.index(), pylonId);
            });
            Location textLocation = pylonLocation.clone().add(0, 1.5, 0);
            textDisplay = clickedBlock.getWorld().spawn(textLocation, TextDisplay.class, display -> {
                configureHologram(display, island.index(), pylonId, core, pylonTier);
            });
            placedPylon = new PylonRecord(
                    island.index(),
                    pylonId,
                    clickedBlock.getWorld().getUID(),
                    pylonLocation.getX(),
                    pylonLocation.getY(),
                    pylonLocation.getZ(),
                    baseDisplay.getUniqueId(),
                    rodDisplay.getUniqueId(),
                    interaction.getUniqueId(),
                    textDisplay.getUniqueId(),
                    pylonTier,
                    equippedCells);
            repository.add(placedPylon, reservedIsland != null);
            refreshPylon(placedPylon);
        } catch (RuntimeException exception) {
            if (placedPylon != null && repository.remove(pylonId) != null && reservedIsland != null) {
                repository.reserveReturnedItem(island.index());
            }
            removeIfPresent(textDisplay);
            removeIfPresent(interaction);
            removeIfPresent(rodDisplay);
            removeIfPresent(baseDisplay);
            plugin.getLogger().log(Level.SEVERE, "Could not place pylon on island " + island.name() + ".", exception);
            messages.send(player, "pylon-place-failed");
            return false;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            heldItem.setAmount(heldItem.getAmount() - 1);
        }
        startPulse(interaction);
        messages.send(player, "pylon-placed");
        return true;
    }

    public void onEntityLoad(org.bukkit.entity.Entity entity) {
        if (entity instanceof Interaction && isPylonEntity(entity)) {
            PylonRecord pylon = pylonFrom(entity);
            CoreRecord core = pylon == null ? null : coreRepository.core(pylon.islandIndex());
            if (pylon != null && core != null) {
                ensurePylonHologram(pylon, entity.getWorld(), core);
            }
            startPulse((Interaction) entity);
        }
    }

    public void initializeLoadedEntities() {
        for (PylonRecord pylon : repository.all()) {
            World world = plugin.getServer().getWorld(pylon.worldId());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, pylon.x(), pylon.y(), pylon.z());
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                org.bukkit.entity.Entity entity = world.getEntity(pylon.interactionId());
                if (entity instanceof Interaction interaction) {
                    CoreRecord core = coreRepository.core(pylon.islandIndex());
                    if (core != null) {
                        ensurePylonHologram(pylon, world, core);
                    }
                    startPulse(interaction);
                }
            });
        }
    }

    private void startPulse(Interaction interaction) {
        UUID pylonId = interaction.getPersistentDataContainer().get(pylonIdKey, PersistentDataType.STRING) == null
                ? null
                : UUID.fromString(interaction.getPersistentDataContainer().get(pylonIdKey, PersistentDataType.STRING));
        PylonRecord pylon = pylonId == null ? null : repository.get(pylonId);
        if (pylon == null || activePylons.containsKey(pylonId)) {
            return;
        }
        ScheduledTask[] taskReference = new ScheduledTask[1];
        long period = effectiveIntervalTicks(pylon);
        ScheduledTask scheduled = interaction.getScheduler().runAtFixedRate(plugin, task -> {
            if (!interaction.isValid()) {
                task.cancel();
                activePylons.remove(pylonId, taskReference[0]);
                return;
            }
            firePulse(interaction, pylonId);
        }, () -> activePylons.remove(pylonId, taskReference[0]), period, period);
        if (scheduled != null) {
            taskReference[0] = scheduled;
            activePylons.put(pylonId, scheduled);
        }
    }

    private void stopPulse(UUID pylonId) {
        ScheduledTask task = activePylons.remove(pylonId);
        if (task != null) {
            task.cancel();
        }
    }

    private void restartPulse(PylonRecord pylon) {
        stopPulse(pylon.pylonId());
        World world = plugin.getServer().getWorld(pylon.worldId());
        if (world == null) {
            return;
        }
        Location location = new Location(world, pylon.x(), pylon.y(), pylon.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            Entity entity = world.getEntity(pylon.interactionId());
            if (entity instanceof Interaction interaction) {
                startPulse(interaction);
            }
        });
    }

    private void firePulse(Interaction interaction, UUID pylonId) {
        PylonRecord pylon = repository.get(pylonId);
        if (pylon == null) {
            return;
        }
        CoreRecord core = coreRepository.core(pylon.islandIndex());
        if (core == null) {
            return;
        }
        World world = plugin.getServer().getWorld(core.worldId());
        if (world == null || !world.getUID().equals(pylon.worldId())) {
            return;
        }
        Location source = interaction.getLocation().add(0, 0.6, 0);
        Location target = new Location(world, core.x(), core.y(), core.z());
        world.playSound(source, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.6f);
        drawPulseLine(world, source, target, dominantCell(pylon));
        drawLightningLinks(pylon, world);
        double yieldRatio = baseYieldRatio * lightningMultiplier(pylon)
                * coreService.energyMultiplier(pylon.islandIndex(), System.currentTimeMillis());
        if (criticalHit(pylon)) {
            yieldRatio *= 3.5;
        }
        CoreRecord updated = coreService.addPassiveEnergy(pylon.islandIndex(), yieldRatio);
        if (updated == null) {
            return;
        }
        plugin.getServer().getRegionScheduler().run(plugin, target, task ->
                world.playSound(target, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.8f));
        updatePylonVisuals(pylon, updated, world);
    }

    private void drawPulseLine(World world, Location source, Location target, CellRecord cell) {
        double dx = target.getX() - source.getX();
        double dy = target.getY() - source.getY();
        double dz = target.getZ() - source.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / 0.5));
        for (int step = 0; step <= steps; step++) {
            double progress = step / (double) steps;
            Location particleLocation = new Location(
                    world,
                    source.getX() + dx * progress,
                    source.getY() + dy * progress,
                    source.getZ() + dz * progress);
            plugin.getServer().getRegionScheduler().run(plugin, particleLocation, task -> {
                Particle particle = cell == null ? Particle.ELECTRIC_SPARK : cell.type().particle();
                world.spawnParticle(particle, particleLocation, 4, 0.025, 0.025, 0.025, 0.01);
                if (cell != null && cell.type() == CellType.FIRE) {
                    world.spawnParticle(Particle.SMOKE, particleLocation, 1, 0.015, 0.015, 0.015, 0.005);
                }
            });
        }
    }

    private void drawLightningLinks(PylonRecord pylon, World world) {
        if (highestCell(pylon, CellType.LIGHTNING) == null) {
            return;
        }
        for (PylonRecord neighbor : repository.forIsland(pylon.islandIndex())) {
            if (neighbor.pylonId().equals(pylon.pylonId())
                    || highestCell(neighbor, CellType.LIGHTNING) == null
                    || distanceSquared(pylon, neighbor) > 36.0) {
                continue;
            }
            Location source = new Location(world, pylon.x(), pylon.y() + 0.3, pylon.z());
            Location target = new Location(world, neighbor.x(), neighbor.y() + 0.3, neighbor.z());
            drawPulseLine(world, source, target, new CellRecord(CellType.LIGHTNING, 1));
        }
    }

    private int effectiveIntervalTicks(PylonRecord pylon) {
        int reduction = 0;
        for (CellRecord cell : pylon.equippedCells().values()) {
            if (cell.type() == CellType.FIRE) {
                reduction += 20 * (1 << (cell.tier() - 1));
            }
        }
        return Math.max(20, intervalTicks - reduction);
    }

    private double lightningMultiplier(PylonRecord pylon) {
        CellRecord ownCell = highestCell(pylon, CellType.LIGHTNING);
        if (ownCell == null) {
            return 1.0;
        }
        double bonus = 0;
        for (PylonRecord neighbor : repository.forIsland(pylon.islandIndex())) {
            if (neighbor.pylonId().equals(pylon.pylonId()) || distanceSquared(pylon, neighbor) > 36.0) {
                continue;
            }
            CellRecord linkedCell = highestCell(neighbor, CellType.LIGHTNING);
            if (linkedCell != null) {
                bonus += lightningBonusForTier(ownCell.tier());
            }
        }
        return 1.0 + bonus;
    }

    private boolean criticalHit(PylonRecord pylon) {
        double chance = pylon.equippedCells().values().stream()
                .filter(cell -> cell.type() == CellType.VOID)
                .mapToDouble(cell -> 0.15 * Math.pow(2.0, cell.tier() - 1))
                .sum();
        return ThreadLocalRandom.current().nextDouble() < Math.min(1.0, chance);
    }

    private CellRecord dominantCell(PylonRecord pylon) {
        return pylon.equippedCells().entrySet().stream()
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    private CellRecord highestCell(PylonRecord pylon, CellType type) {
        return pylon.equippedCells().values().stream()
                .filter(cell -> cell.type() == type)
                .max(java.util.Comparator.comparingInt(CellRecord::tier))
                .orElse(null);
    }

    private double distanceSquared(PylonRecord first, PylonRecord second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private void configureDisplay(
            ItemDisplay display, Material material, float scale, long islandIndex, UUID pylonId) {
        display.setItemStack(new ItemStack(material));
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()));
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(true);
        markEntity(display, islandIndex, pylonId);
    }

    private void ensurePylonHologram(PylonRecord pylon, World world, CoreRecord core) {
        Entity existing = pylon.textDisplayId() == null ? null : world.getEntity(pylon.textDisplayId());
        if (existing instanceof TextDisplay display) {
            display.teleport(new Location(world, pylon.x(), pylon.y() + 1.5, pylon.z()));
            display.text(pylonHologram(core, pylon));
            updatePylonVisuals(pylon, core, world);
            return;
        }
        Location textLocation = new Location(world, pylon.x(), pylon.y() + 1.5, pylon.z());
        TextDisplay display = world.spawn(textLocation, TextDisplay.class, spawned ->
                configureHologram(spawned, pylon.islandIndex(), pylon.pylonId(), core, pylon.tier()));
        repository.update(new PylonRecord(
                pylon.islandIndex(),
                pylon.pylonId(),
                pylon.worldId(),
                pylon.x(),
                pylon.y(),
                pylon.z(),
                pylon.itemDisplayId(),
                pylon.rodDisplayId(),
                pylon.interactionId(),
                display.getUniqueId(),
                pylon.tier(),
                pylon.equippedCells()));
        updatePylonVisuals(repository.get(pylon.pylonId()), core, world);
    }

    private PylonRecord pylonFrom(Entity entity) {
        String id = entity.getPersistentDataContainer().get(pylonIdKey, PersistentDataType.STRING);
        return id == null ? null : repository.get(UUID.fromString(id));
    }

    private void configureHologram(
            TextDisplay display, long islandIndex, UUID pylonId, CoreRecord core, int tier) {
        display.text(pylonHologram(core, tier, Map.of(), 1.0));
        display.setBillboard(Display.Billboard.CENTER);
        display.setGravity(false);
        display.setPersistent(true);
        markEntity(display, islandIndex, pylonId);
    }

    private Component pylonHologram(CoreRecord core, PylonRecord pylon) {
        return pylonHologram(core, pylon.tier(), pylon.equippedCells(), lightningMultiplier(pylon));
    }

    private Component pylonHologram(
            CoreRecord core, int tier, Map<Integer, CellRecord> cells, double lightningMultiplier) {
        Component text = messages.parseComponent("&bPilón de Resonancia &7[T" + tier + "]");
        if (!cells.isEmpty()) {
            text = text.append(Component.newline()).append(messages.parseComponent(
                    "&7Células equipadas: &f" + cells.size()));
        }
        double yield = core.baseEnergy() * baseYieldRatio * lightningMultiplier;
        return text.append(Component.newline())
                .append(messages.parseComponent("&e+" + formatEnergy(yield) + "⚡ / pulso"));
    }

    private double lightningBonusForTier(int tier) {
        return 0.25 * Math.pow(2.0, tier - 1);
    }

    private void updatePylonVisuals(PylonRecord pylon, CoreRecord core, World world) {
        Entity baseEntity = world.getEntity(pylon.itemDisplayId());
        CellRecord cell = dominantCell(pylon);
        if (baseEntity instanceof ItemDisplay baseDisplay) {
            Material material = cell == null ? baseMaterial : cell.type().baseMaterial();
            baseDisplay.setItemStack(new ItemStack(material));
        }
        if (pylon.textDisplayId() == null) {
            return;
        }
        Location location = new Location(world, pylon.x(), pylon.y() + 1.5, pylon.z());
        Entity textEntity = world.getEntity(pylon.textDisplayId());
        if (textEntity instanceof TextDisplay display) {
            display.teleport(location);
            display.text(pylonHologram(core, pylon));
        }
    }

    private void markEntity(org.bukkit.entity.Entity entity, long islandIndex, UUID pylonId) {
        entity.getPersistentDataContainer().set(pylonEntityKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "pylon_island_index"), PersistentDataType.LONG, islandIndex);
        entity.getPersistentDataContainer().set(pylonIdKey, PersistentDataType.STRING, pylonId.toString());
    }

    private ItemStack createPylonItem(long islandIndex) {
        return createPylonItem(TIER_ONE, Map.of(), islandIndex);
    }

    private ItemStack createPylonItem(PylonRecord pylon) {
        return createPylonItem(pylon.tier(), pylon.equippedCells(), pylon.islandIndex());
    }

    private ItemStack createPylonItem(int tier, Map<Integer, CellRecord> cells, long islandIndex) {
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(config.getString(
                "pylon.item.display-name", "&bPilón de Resonancia") + " &7[T" + tier + "]"));
        meta.lore(config.getStringList("pylon.item.lore").stream()
                .map(messages::parseComponent)
                .toList());
        meta.getPersistentDataContainer().set(pylonItemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(pylonTierKey, PersistentDataType.INTEGER, tier);
        meta.getPersistentDataContainer().set(pylonCellsKey, PersistentDataType.STRING, encodeCells(cells));
        meta.getPersistentDataContainer().set(pylonIslandKey, PersistentDataType.LONG, islandIndex);
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }

    private int pylonTier(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return TIER_ONE;
        }
        Integer tier = item.getItemMeta().getPersistentDataContainer().get(pylonTierKey, PersistentDataType.INTEGER);
        return tier == null || tier < 1 || tier > 3 ? TIER_ONE : tier;
    }

    private Map<Integer, CellRecord> pylonCells(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Map.of();
        }
        String encoded = item.getItemMeta().getPersistentDataContainer().get(pylonCellsKey, PersistentDataType.STRING);
        if (encoded == null || encoded.isBlank()) {
            return Map.of();
        }
        Map<Integer, CellRecord> cells = new java.util.HashMap<>();
        for (String entry : encoded.split(",")) {
            String[] parts = entry.split(":", 3);
            if (parts.length != 3) {
                continue;
            }
            try {
                int slot = Integer.parseInt(parts[0]);
                CellRecord cell = new CellRecord(CellType.valueOf(parts[1]), Integer.parseInt(parts[2]));
                if (slot >= 0 && slot < Math.min(pylonTier(item), 3)) {
                    cells.put(slot, cell);
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid saved cell data on a pylon item.");
            }
        }
        return Map.copyOf(cells);
    }

    private String encodeCells(Map<Integer, CellRecord> cells) {
        return cells.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().type().name() + ":" + entry.getValue().tier())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Material pylonBaseMaterial(Map<Integer, CellRecord> cells) {
        return cells.entrySet().stream()
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .map(cell -> cell.type().baseMaterial())
                .orElse(baseMaterial);
    }

    private Material configuredMaterial(String path, String fallback, boolean mustBeItem) {
        Material material = Material.matchMaterial(config.getString(path, fallback));
        if (material == null || (mustBeItem && !material.isItem()) || (!mustBeItem && !material.isBlock())) {
            throw new IllegalArgumentException("pylons.yml " + path + " must be a valid "
                    + (mustBeItem ? "item." : "block material."));
        }
        return material;
    }

    private String formatEnergy(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private void removeIfPresent(org.bukkit.entity.Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void showRangeGuide(Player player) {
        if (!player.isOnline() || !isPylonItem(player.getInventory().getItemInMainHand())) {
            return;
        }
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null) {
            return;
        }
        CoreRecord core = coreRepository.core(island.index());
        if (core == null) {
            return;
        }
        World world = plugin.getServer().getWorld(core.worldId());
        if (world == null || !world.getUID().equals(player.getWorld().getUID())) {
            return;
        }
        drawRangeCircle(world, core, minimumDistance, Particle.FLAME);
        drawRangeCircle(world, core, maximumDistance, Particle.END_ROD);
    }

    private void drawRangeCircle(World world, CoreRecord core, double radius, Particle particle) {
        for (int point = 0; point < RANGE_GUIDE_POINTS; point++) {
            double angle = 2.0 * Math.PI * point / RANGE_GUIDE_POINTS;
            Location particleLocation = new Location(
                    world,
                    core.x() + Math.cos(angle) * radius,
                    core.y() + 0.5,
                    core.z() + Math.sin(angle) * radius);
            plugin.getServer().getRegionScheduler().run(plugin, particleLocation, task ->
                    world.spawnParticle(particle, particleLocation, 2, 0.04, 0.08, 0.04, 0));
        }
    }

    public void flush() throws IOException {
        repository.flush();
    }
}
