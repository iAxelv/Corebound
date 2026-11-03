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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CondenserService implements Listener {
    private static final long RANGE_GUIDE_PERIOD_TICKS = 12L;
    private static final List<Material> ORES = List.of(
            Material.COAL_ORE,
            Material.COPPER_ORE,
            Material.IRON_ORE,
            Material.GOLD_ORE,
            Material.REDSTONE_ORE,
            Material.LAPIS_ORE,
            Material.EMERALD_ORE,
            Material.DIAMOND_ORE,
            Material.ANCIENT_DEBRIS);

    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final CoreService coreService;
    private final CoreRepository coreRepository;
    private final CondenserRepository repository;
    private final PylonService pylons;
    private final Messages messages;
    private final File configFile;
    private final NamespacedKey itemKey;
    private final NamespacedKey itemIslandKey;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey itemLevelKey;
    private final NamespacedKey itemStateKey;
    private final NamespacedKey entityKey;
    private final NamespacedKey entityIdKey;
    private final Map<UUID, ScheduledTask> monitorTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> visualTasks = new ConcurrentHashMap<>();
    private final Set<UUID> rangeGuidePlayers = ConcurrentHashMap.newKeySet();
    private final CondenserMenu menu;
    private final CondenserRuneMenu runeMenu;
    private YamlConfiguration config;
    private Material itemMaterial;
    private double displayScale;
    private double displayHeightOffset;
    private int rotationSpeedTicks;
    private double purchaseCost;
    private double upgradeBaseCost;
    private double upgradeCostMultiplier;
    private int maximumLevel;
    private double oreChance;
    private double baseEnergyCost;
    private double energyCostPerLevel;
    private int minimumCoreLevel;
    private int pickupLockCoreLevel;

    public CondenserService(
            TheCorePlugin plugin,
            IslandService islandService,
            CoreService coreService,
            CoreRepository coreRepository,
            CondenserRepository repository,
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
        itemKey = new NamespacedKey(plugin, "condenser_item");
        itemIslandKey = new NamespacedKey(plugin, "condenser_item_island");
        itemIdKey = new NamespacedKey(plugin, "condenser_item_id");
        itemLevelKey = new NamespacedKey(plugin, "condenser_item_level");
        itemStateKey = new NamespacedKey(plugin, "condenser_item_state");
        entityKey = new NamespacedKey(plugin, "condenser_entity");
        entityIdKey = new NamespacedKey(plugin, "condenser_id");
        applyConfiguration(loadConfiguration(true));
        menu = new CondenserMenu(messages, this);
        runeMenu = new CondenserRuneMenu(messages, this, pylons);
    }

    public void reloadConfiguration() {
        applyConfiguration(loadConfiguration(false));
        repository.all().forEach(this::refreshVisuals);
    }

    private YamlConfiguration loadConfiguration(boolean saveDefaults) {
        YamlConfiguration loaded = ConfigFiles.load(configFile);
        InputStream defaultsStream = plugin.getResource("core/condenser.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/condenser.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/condenser.yml defaults.", exception);
        }
        return loaded;
    }

    private void applyConfiguration(YamlConfiguration loaded) {
        Material loadedItemMaterial = configuredMaterial(loaded, "condenser.item.material", "BLAST_FURNACE", true);
        double loadedScale = loaded.getDouble("condenser.display.scale", 1.2);
        double loadedHeightOffset = loaded.getDouble("condenser.display.height-offset", 1.8);
        int loadedRotationSpeedTicks = loaded.getInt("condenser.display.rotation-speed-ticks", 20);
        double loadedPurchaseCost = loaded.getDouble("condenser.purchase-cost", 500.0);
        double loadedUpgradeBaseCost = loaded.getDouble("condenser.upgrade.base-cost", 500.0);
        double loadedUpgradeMultiplier = loaded.getDouble("condenser.upgrade.cost-multiplier", 1.8);
        int loadedMaximumLevel = loaded.getInt("condenser.max-level", ORES.size());
        if (loadedMaximumLevel == ORES.size() - 1) {
            loadedMaximumLevel = ORES.size();
            loaded.set("condenser.max-level", loadedMaximumLevel);
            try {
                loaded.save(configFile);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not migrate condenser.yml to unlock all ores.", exception);
            }
        }
        double loadedOreChance = loaded.getDouble("condenser.generation.ore-chance", 0.10);
        double loadedBaseEnergyCost = loaded.getDouble("condenser.generation.energy-cost", 1.0);
        double loadedEnergyCostPerLevel = loaded.getDouble("condenser.generation.energy-cost-per-level", 0.5);
        int loadedMinimumCoreLevel = loaded.getInt("condenser.minimum-core-level", 3);
        int loadedPickupLockCoreLevel = loaded.getInt("condenser.pickup-lock-core-level", 10);
        if (!Double.isFinite(loadedScale) || loadedScale <= 0
                || !Double.isFinite(loadedHeightOffset) || loadedHeightOffset < 1 || loadedHeightOffset >= 2
                || loadedRotationSpeedTicks <= 0 || !Double.isFinite(loadedPurchaseCost)
                || loadedPurchaseCost <= 0 || !Double.isFinite(loadedUpgradeBaseCost) || loadedUpgradeBaseCost <= 0
                || !Double.isFinite(loadedUpgradeMultiplier) || loadedUpgradeMultiplier <= 1
                || loadedMaximumLevel < 0 || loadedMaximumLevel > ORES.size()
                || !Double.isFinite(loadedOreChance) || loadedOreChance < 0 || loadedOreChance > 1
                || !Double.isFinite(loadedBaseEnergyCost) || loadedBaseEnergyCost <= 0
                || !Double.isFinite(loadedEnergyCostPerLevel) || loadedEnergyCostPerLevel < 0
                || loadedMinimumCoreLevel < 1 || loadedPickupLockCoreLevel < 1) {
            throw new IllegalArgumentException("condenser.yml contains invalid cost, level, or generation settings.");
        }
        int configuredMaximumLevel = loadedMaximumLevel;
        if (repository.all().stream().anyMatch(condenser -> condenser.level() > configuredMaximumLevel)) {
            throw new IllegalArgumentException(
                    "condenser.yml max-level cannot be lower than an existing condenser level.");
        }
        config = loaded;
        itemMaterial = loadedItemMaterial;
        displayScale = (float) loadedScale;
        displayHeightOffset = loadedHeightOffset;
        rotationSpeedTicks = loadedRotationSpeedTicks;
        purchaseCost = loadedPurchaseCost;
        upgradeBaseCost = loadedUpgradeBaseCost;
        upgradeCostMultiplier = loadedUpgradeMultiplier;
        maximumLevel = loadedMaximumLevel;
        oreChance = loadedOreChance;
        baseEnergyCost = loadedBaseEnergyCost;
        energyCostPerLevel = loadedEnergyCostPerLevel;
        minimumCoreLevel = loadedMinimumCoreLevel;
        pickupLockCoreLevel = loadedPickupLockCoreLevel;
    }

    private Material configuredMaterial(YamlConfiguration loaded, String path, String fallback, boolean item) {
        Material material = Material.matchMaterial(loaded.getString(path, fallback));
        if (material == null || (item && !material.isItem()) || (!item && !material.isBlock())) {
            throw new IllegalArgumentException("condenser.yml " + path + " must be a valid "
                    + (item ? "item." : "block material."));
        }
        return material;
    }

    public CondenserMenu menuListener() {
        return menu;
    }

    public CondenserRuneMenu runeMenuListener() {
        return runeMenu;
    }

    public double purchaseCost() {
        return purchaseCost;
    }

    public boolean isCondenserItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public boolean isCondenserEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(entityKey, PersistentDataType.BYTE);
    }

    public boolean buy(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "condenser-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(islandIndex);
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < minimumCoreLevel) {
            messages.send(player, "condenser-core-level-required", Map.of(
                    "level", Integer.toString(minimumCoreLevel)));
            return false;
        }
        UUID itemId = repository.reserveItem(islandIndex);
        if (itemId == null) {
            messages.send(player, "condenser-already-exists");
            return false;
        }
        CoreRecord chargedCore = coreRepository.spendEnergy(islandIndex, purchaseCost);
        if (chargedCore == null) {
            repository.releasePendingItem(islandIndex, itemId);
            messages.send(player, "condenser-insufficient-energy");
            return false;
        }
        try {
            deliverItem(player, createItem(islandIndex, itemId, 0, CondenserState.ACTIVE));
        } catch (RuntimeException exception) {
            repository.releasePendingItem(islandIndex, itemId);
            coreRepository.restoreEnergy(islandIndex, purchaseCost);
            plugin.getLogger().log(Level.SEVERE, "Could not deliver a purchased matter condenser.", exception);
            messages.send(player, "condenser-purchase-failed");
            return false;
        }
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        messages.send(player, "condenser-purchased", Map.of("cost", formatEnergy(purchaseCost)));
        return true;
    }

    private void deliverItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            dropped.setPersistent(true);
        }
    }

    private ItemStack createItem(long islandIndex, UUID itemId, int level, CondenserState state) {
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&bCondensador de Materia"));
        meta.lore(List.of(
                messages.parseComponent("&7Nivel: &e" + level + "/" + maximumLevel),
                messages.parseComponent("&7Estado: &a" + state.name()),
                messages.parseComponent("&7Colocación: entre &e"
                        + formatEnergy(pylons.minimumDistance()) + " y &e"
                        + formatEnergy(pylons.maximumDistance()) + " bloques del Núcleo."),
                messages.parseComponent("&8Mayús+clic derecho para recogerlo.")));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(itemIslandKey, PersistentDataType.LONG, islandIndex);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, itemId.toString());
        meta.getPersistentDataContainer().set(itemLevelKey, PersistentDataType.INTEGER, level);
        meta.getPersistentDataContainer().set(itemStateKey, PersistentDataType.STRING, state.name());
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean place(Player player, Block clickedBlock) {
        if (!clickedBlock.getType().isSolid()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP).getType().isAir()
                || !clickedBlock.getRelative(org.bukkit.block.BlockFace.UP, 2).getType().isAir()) {
            messages.send(player, "condenser-needs-clearance");
            return false;
        }
        IslandData island = islandService.islandAt(clickedBlock.getLocation()).orElse(null);
        if (island == null || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "condenser-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(island.index());
        if (core == null) {
            messages.send(player, "core-already-placed");
            return false;
        }
        if (core.level() < minimumCoreLevel) {
            messages.send(player, "condenser-core-level-required", Map.of(
                    "level", Integer.toString(minimumCoreLevel)));
            return false;
        }
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        ItemMeta heldMeta = heldItem.getItemMeta();
        UUID itemId = parseUuid(heldMeta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING));
        Long boundIsland = heldMeta.getPersistentDataContainer().get(itemIslandKey, PersistentDataType.LONG);
        if (boundIsland == null || boundIsland != island.index()) {
            messages.send(player, "condenser-item-wrong-island");
            return false;
        }
        if (!repository.canPlace(island.index(), itemId)) {
            messages.send(player, "condenser-item-invalidated");
            return false;
        }
        Location condenserLocation = new Location(
                clickedBlock.getWorld(),
                clickedBlock.getX() + 0.5,
                clickedBlock.getY() + displayHeightOffset,
                clickedBlock.getZ() + 0.5);
        double distance = condenserLocation.distance(new Location(
                clickedBlock.getWorld(), core.x(), core.y(), core.z()));
        if (distance < pylons.minimumDistance() || distance > pylons.maximumDistance()) {
            messages.send(player, "condenser-distance-invalid", Map.of(
                    "min", formatEnergy(pylons.minimumDistance()),
                    "max", formatEnergy(pylons.maximumDistance())));
            return false;
        }

        UUID condenserId = UUID.randomUUID();
        int level = heldMeta.getPersistentDataContainer().getOrDefault(
                itemLevelKey, PersistentDataType.INTEGER, 0);
        if (level < 0 || level > maximumLevel) {
            messages.send(player, "condenser-item-invalidated");
            return false;
        }
        String stateName = heldMeta.getPersistentDataContainer().getOrDefault(
                itemStateKey, PersistentDataType.STRING, CondenserState.ACTIVE.name());
        CondenserState state = parseState(stateName);
        if (state == null) {
            messages.send(player, "condenser-item-invalidated");
            return false;
        }
        ItemDisplay itemDisplay = null;
        Interaction interaction = null;
        TextDisplay textDisplay = null;
        try {
            itemDisplay = clickedBlock.getWorld().spawn(condenserLocation, ItemDisplay.class, display -> {
                configureDisplay(display);
                markEntity(display, condenserId);
            });
            Location interactionLocation = condenserLocation.clone().subtract(0, 0.6, 0);
            interaction = clickedBlock.getWorld().spawn(interactionLocation, Interaction.class, entity -> {
                entity.setInteractionWidth(1.0f);
                entity.setInteractionHeight(1.5f);
                entity.setResponsive(true);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(true);
                markEntity(entity, condenserId);
            });
            Location textLocation = condenserLocation.clone().add(0, 1.1, 0);
            textDisplay = clickedBlock.getWorld().spawn(textLocation, TextDisplay.class, display -> {
                display.setBillboard(Display.Billboard.CENTER);
                display.setGravity(false);
                display.setPersistent(true);
                display.setInvulnerable(true);
                markEntity(display, condenserId);
            });
            CondenserRecord condenser = new CondenserRecord(
                    island.index(),
                    condenserId,
                    clickedBlock.getWorld().getUID(),
                    condenserLocation.getX(),
                    condenserLocation.getY(),
                    condenserLocation.getZ(),
                    itemDisplay.getUniqueId(),
                    interaction.getUniqueId(),
                    textDisplay.getUniqueId(),
                    level,
                    state,
                    1,
                    Map.of());
            repository.add(condenser, itemId);
            refreshVisuals(condenser);
            consumeHeldItem(player, heldItem);
            startMonitor(interaction, condenserId);
            startVisualAnimation(itemDisplay, condenserId);
            messages.send(player, "condenser-placed");
            return true;
        } catch (RuntimeException exception) {
            CondenserRecord placed = repository.get(island.index());
            if (placed != null && placed.condenserId().equals(condenserId)) {
                repository.rollbackPlacement(island.index(), condenserId, itemId);
            }
            removeIfPresent(textDisplay);
            removeIfPresent(interaction);
            removeIfPresent(itemDisplay);
            plugin.getLogger().log(Level.SEVERE, "Could not place matter condenser on island " + island.name(),
                    exception);
            messages.send(player, "condenser-place-failed");
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
                                    && isCondenserItem(player.getInventory().getItemInMainHand())) {
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
        for (CondenserRecord condenser : repository.all()) {
            World world = plugin.getServer().getWorld(condenser.worldId());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, condenser.x(), condenser.y(), condenser.z());
            plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
                Entity entity = world.getEntity(condenser.interactionId());
                if (entity instanceof Interaction interaction) {
                    refreshVisuals(condenser);
                    startMonitor(interaction, condenser.condenserId());
                }
            });
        }
    }

    public void onEntityLoad(Entity entity) {
        if (!isCondenserEntity(entity)) {
            return;
        }
        CondenserRecord condenser = condenserFrom(entity);
        if (condenser == null) {
            removeIfPresent(entity);
            return;
        }
        if (entity instanceof ItemDisplay display) {
            configureDisplay(display);
            startVisualAnimation(display, condenser.condenserId());
        } else if (entity instanceof Interaction interaction) {
            refreshVisuals(condenser);
            startMonitor(interaction, condenser.condenserId());
        }
    }

    private CondenserRecord condenserFrom(Entity entity) {
        String id = entity.getPersistentDataContainer().get(entityIdKey, PersistentDataType.STRING);
        UUID condenserId = parseUuid(id);
        return condenserId == null ? null : repository.getById(condenserId);
    }

    private void markEntity(Entity entity, UUID condenserId) {
        entity.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(entityIdKey, PersistentDataType.STRING, condenserId.toString());
    }

    private void startMonitor(Interaction interaction, UUID condenserId) {
        if (monitorTasks.containsKey(condenserId)) {
            return;
        }
        ScheduledTask[] taskReference = new ScheduledTask[1];
        ScheduledTask task = interaction.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!interaction.isValid()) {
                scheduled.cancel();
                monitorTasks.remove(condenserId, taskReference[0]);
                return;
            }
            CondenserRecord condenser = repository.getById(condenserId);
            if (condenser == null) {
                scheduled.cancel();
                monitorTasks.remove(condenserId, taskReference[0]);
                return;
            }
            if (condenser.state() == CondenserState.IDLE) {
                CoreRecord core = coreRepository.core(condenser.islandIndex());
                if (core != null && core.energy() >= energyCost(condenser)) {
                    CondenserRecord active = condenser.withState(CondenserState.ACTIVE);
                    repository.update(active);
                    refreshVisuals(active);
                }
            }
        }, () -> monitorTasks.remove(condenserId, taskReference[0]), 20L, 20L);
        if (task != null) {
            taskReference[0] = task;
            monitorTasks.put(condenserId, task);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Material vanillaResult = event.getNewState().getType();
        if (vanillaResult != Material.COBBLESTONE && vanillaResult != Material.STONE) {
            return;
        }
        IslandData island = islandService.islandAt(event.getBlock().getLocation()).orElse(null);
        if (island == null) {
            return;
        }
        CondenserRecord condenser = repository.get(island.index());
        if (condenser == null || condenser.state() != CondenserState.ACTIVE) {
            return;
        }
        CoreRecord chargedCore = coreRepository.spendEnergy(
                island.index(), energyCost(condenser));
        if (chargedCore == null) {
            CondenserRecord idle = condenser.withState(CondenserState.IDLE);
            repository.update(idle);
            refreshVisuals(idle);
            return;
        }
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        double effectiveOreChance = mineralChance(condenser);
        if (condenser.level() > 0 && ThreadLocalRandom.current().nextDouble() < effectiveOreChance) {
            int availableOreCount = Math.min(condenser.level(), ORES.size());
            event.getNewState().setType(ORES.get(ThreadLocalRandom.current().nextInt(availableOreCount)));
        }
    }

    public boolean openMenu(Player player, Entity entity) {
        CondenserRecord condenser = condenserFrom(entity);
        if (condenser == null || !canManage(player, condenser)) {
            return false;
        }
        menu.open(player, condenser);
        return true;
    }

    public void openMenu(Player player, UUID condenserId) {
        CondenserRecord condenser = repository.getById(condenserId);
        if (condenser != null && canManage(player, condenser)) {
            menu.open(player, condenser);
        }
    }

    public void openRuneMenu(Player player, UUID condenserId) {
        CondenserRecord condenser = repository.getById(condenserId);
        if (condenser != null && canManage(player, condenser)) {
            runeMenu.open(player, condenser);
        }
    }

    public boolean pickup(Player player, Entity entity) {
        CondenserRecord condenser = condenserFrom(entity);
        if (condenser == null) {
            return false;
        }
        IslandData island = islandService.islandAt(entity.getLocation()).orElse(null);
        if (island == null || island.index() != condenser.islandIndex()
                || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "condenser-access-denied");
            return false;
        }
        CoreRecord core = coreRepository.core(condenser.islandIndex());
        if (core != null && core.level() >= pickupLockCoreLevel) {
            messages.send(player, "condenser-max-level-pickup-locked");
            return false;
        }
        UUID itemId = repository.removeAndReserveItem(condenser.islandIndex(), condenser.condenserId());
        if (itemId == null) {
            return false;
        }
        try {
            deliverItem(player, createItem(
                    condenser.islandIndex(), itemId, condenser.level(), condenser.state()));
        } catch (RuntimeException exception) {
            repository.restore(condenser, itemId);
            plugin.getLogger().log(Level.SEVERE, "Could not return a picked-up matter condenser.", exception);
            messages.send(player, "condenser-pickup-failed");
            return false;
        }
        ScheduledTask monitor = monitorTasks.remove(condenser.condenserId());
        if (monitor != null) {
            monitor.cancel();
        }
        ScheduledTask visual = visualTasks.remove(condenser.condenserId());
        if (visual != null) {
            visual.cancel();
        }
        World world = plugin.getServer().getWorld(condenser.worldId());
        if (world != null) {
            removeIfPresent(world.getEntity(condenser.itemDisplayId()));
            removeIfPresent(world.getEntity(condenser.interactionId()));
            removeIfPresent(world.getEntity(condenser.textDisplayId()));
        }
        messages.send(player, "condenser-picked-up");
        return true;
    }

    public CondenserRecord record(UUID condenserId) {
        return repository.getById(condenserId);
    }

    public boolean canManage(Player player, CondenserRecord condenser) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != condenser.islandIndex()
                || !island.canBuild(player.getUniqueId())) {
            messages.send(player, "condenser-access-denied");
            return false;
        }
        return true;
    }

    public boolean canRemoveRunes(Player player, CondenserRecord condenser) {
        CoreRecord core = coreRepository.core(condenser.islandIndex());
        if (core != null && core.level() >= pickupLockCoreLevel) {
            messages.send(player, "condenser-runes-locked");
            return false;
        }
        return canManage(player, condenser);
    }

    public boolean canUseRuneTier(Player player, CondenserRecord condenser, int tier) {
        if (!canManage(player, condenser)) {
            return false;
        }
        int maximumTier = CellRecord.maximumTierForCondenserLevel(condenser.level());
        if (tier > maximumTier) {
            messages.send(player, "condenser-rune-tier-locked", Map.of(
                    "tier", Integer.toString(tier),
                    "max_tier", Integer.toString(maximumTier),
                    "level", Integer.toString(condenser.level())));
            return false;
        }
        return true;
    }

    public boolean setRune(UUID condenserId, int slot, CellRecord rune) {
        CondenserRecord condenser = repository.getById(condenserId);
        if (condenser == null || slot < 0 || slot >= condenser.runeSlots()
                || (rune != null && !rune.type().isCondenserRune())) {
            return false;
        }
        repository.update(condenser.withRune(slot, rune));
        return true;
    }

    public boolean upgradeRuneSlots(Player player, UUID condenserId) {
        CondenserRecord condenser = repository.getById(condenserId);
        if (condenser == null || !canManage(player, condenser)) {
            return false;
        }
        int nextSlots = condenser.runeSlots() + 1;
        if (nextSlots > 3) {
            messages.send(player, "condenser-rune-slots-max");
            return false;
        }
        int requiredLevel = runeSlotRequiredLevel(nextSlots);
        if (condenser.level() < requiredLevel) {
            messages.send(player, "condenser-rune-slot-locked", Map.of(
                    "level", Integer.toString(requiredLevel)));
            return false;
        }
        double cost = runeSlotUpgradeCost(nextSlots);
        CoreRecord chargedCore = coreRepository.spendEnergy(condenser.islandIndex(), cost);
        if (chargedCore == null) {
            messages.send(player, "condenser-insufficient-energy");
            return false;
        }
        repository.update(condenser.withRuneSlots(nextSlots));
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        messages.send(player, "condenser-rune-slots-upgraded", Map.of(
                "slots", Integer.toString(nextSlots),
                "cost", formatEnergy(cost)));
        return true;
    }

    public int runeSlotRequiredLevel(int slots) {
        return config.getInt("condenser.runes.slot-required-level." + slots, slots == 2 ? 3 : 6);
    }

    public double runeSlotUpgradeCost(int slots) {
        return config.getDouble("condenser.runes.slot-costs." + slots, slots == 2 ? 1000.0 : 4000.0);
    }

    public boolean destroyForFracture(Player player, long islandIndex) {
        IslandData island = islandService.islandAt(player.getLocation()).orElse(null);
        if (island == null || island.index() != islandIndex || !island.owner().equals(player.getUniqueId())) {
            messages.send(player, "core-fracture-owner-only");
            return false;
        }
        CondenserRecord removed = repository.removeForFracture(islandIndex);
        if (removed != null) {
            cancelTasks(removed.condenserId());
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
            if (!isCondenserItem(item)) {
                continue;
            }
            Long boundIsland = item.getItemMeta().getPersistentDataContainer()
                    .get(itemIslandKey, PersistentDataType.LONG);
            if (boundIsland != null && boundIsland == islandIndex) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void cancelTasks(UUID condenserId) {
        ScheduledTask monitor = monitorTasks.remove(condenserId);
        if (monitor != null) {
            monitor.cancel();
        }
        ScheduledTask visual = visualTasks.remove(condenserId);
        if (visual != null) {
            visual.cancel();
        }
    }

    public boolean toggleEnabled(Player player, UUID condenserId) {
        CondenserRecord condenser = repository.getById(condenserId);
        if (condenser == null || !canManage(player, condenser)) {
            return false;
        }
        CondenserState nextState = condenser.state().nextManualState();
        CondenserRecord updated = condenser.withState(nextState);
        repository.update(updated);
        refreshVisuals(updated);
        return true;
    }

    public boolean upgrade(Player player, UUID condenserId) {
        CondenserRecord condenser = repository.getById(condenserId);
        if (condenser == null || !canManage(player, condenser)) {
            return false;
        }
        if (condenser.level() >= maximumLevel) {
            messages.send(player, "condenser-max-level");
            return false;
        }
        double cost = upgradeCost(condenser.level());
        CoreRecord chargedCore = coreRepository.spendEnergy(condenser.islandIndex(), cost);
        if (chargedCore == null) {
            messages.send(player, "condenser-insufficient-energy");
            return false;
        }
        CondenserRecord updated = condenser.withLevel(condenser.level() + 1);
        repository.update(updated);
        coreService.refreshCore(chargedCore, System.currentTimeMillis());
        refreshVisuals(updated);
        messages.send(player, "condenser-upgraded", Map.of(
                "level", Integer.toString(updated.level()),
                "cost", formatEnergy(cost)));
        return true;
    }

    public double upgradeCost(int currentLevel) {
        return upgradeBaseCost * Math.pow(upgradeCostMultiplier, currentLevel);
    }

    public double energyCost(int level) {
        return baseEnergyCost + energyCostPerLevel * level;
    }

    public double energyCost(CondenserRecord condenser) {
        return CondenserMath.energyCost(condenser, baseEnergyCost, energyCostPerLevel);
    }

    public double mineralChance(CondenserRecord condenser) {
        if (condenser.level() == 0) {
            return 0.0;
        }
        return 1.0 - CondenserMath.outputChances(condenser, oreChance)[0] / 100.0;
    }

    public List<Material> unlockedMaterials(int level) {
        List<Material> materials = new java.util.ArrayList<>();
        materials.add(Material.COBBLESTONE);
        if (level > 0) {
            materials.addAll(ORES.subList(0, Math.min(level, ORES.size())));
        }
        return List.copyOf(materials);
    }

    public double materialChance(CondenserRecord condenser, Material material) {
        double[] chances = CondenserMath.outputChances(condenser, oreChance);
        if (material == Material.COBBLESTONE) {
            return chances[0];
        }
        int unlockedOreCount = Math.min(condenser.level(), ORES.size());
        for (int oreIndex = 0; oreIndex < unlockedOreCount; oreIndex++) {
            if (ORES.get(oreIndex) == material) {
                return chances[oreIndex + 1];
            }
        }
        return 0.0;
    }

    public String oreMessageKey(Material material) {
        if (material == Material.COBBLESTONE) {
            return "condenser-ore-stone";
        }
        return switch (material) {
            case COAL_ORE -> "condenser-ore-coal";
            case COPPER_ORE -> "condenser-ore-copper";
            case IRON_ORE -> "condenser-ore-iron";
            case GOLD_ORE -> "condenser-ore-gold";
            case REDSTONE_ORE -> "condenser-ore-redstone";
            case LAPIS_ORE -> "condenser-ore-lapis";
            case EMERALD_ORE -> "condenser-ore-emerald";
            case DIAMOND_ORE -> "condenser-ore-diamond";
            case ANCIENT_DEBRIS -> "condenser-ore-ancient-debris";
            default -> throw new IllegalArgumentException("Unsupported condenser output material: " + material);
        };
    }

    public int maximumLevel() {
        return maximumLevel;
    }

    public int minimumCoreLevel() {
        return minimumCoreLevel;
    }

    private void refreshVisuals(CondenserRecord condenser) {
        World world = plugin.getServer().getWorld(condenser.worldId());
        if (world == null) {
            return;
        }
        double blockY = Math.floor(condenser.y()) - 1;
        Location location = new Location(
                world, condenser.x(), blockY + displayHeightOffset, condenser.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            Entity displayEntity = world.getEntity(condenser.itemDisplayId());
            if (displayEntity instanceof ItemDisplay display) {
                display.teleport(location);
                configureDisplay(display);
                startVisualAnimation(display, condenser.condenserId());
            }
            Entity interactionEntity = world.getEntity(condenser.interactionId());
            if (interactionEntity instanceof Interaction interaction) {
                interaction.teleport(location.clone().subtract(0, 0.6, 0));
            }
            Entity textEntity = world.getEntity(condenser.textDisplayId());
            if (textEntity instanceof TextDisplay display) {
                display.teleport(location.clone().add(0, 1.1, 0));
                display.text(hologram(condenser));
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

    private void startVisualAnimation(ItemDisplay display, UUID condenserId) {
        if (visualTasks.containsKey(condenserId)) {
            return;
        }
        float[] yaw = {0.0f};
        int[] particleTicks = {0};
        ScheduledTask[] taskReference = new ScheduledTask[1];
        ScheduledTask task = display.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!display.isValid()) {
                scheduled.cancel();
                visualTasks.remove(condenserId, taskReference[0]);
                return;
            }
            yaw[0] += (float) (Math.PI / (2.0 * rotationSpeedTicks));
            Transformation current = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformation(new Transformation(
                    new Vector3f(current.getTranslation()),
                    new Quaternionf(current.getLeftRotation()),
                    new Vector3f(current.getScale()),
                    new Quaternionf().rotateY(yaw[0])));
            if (++particleTicks[0] >= 10) {
                particleTicks[0] = 0;
                display.getWorld().spawnParticle(
                        Particle.ELECTRIC_SPARK, display.getLocation().add(0, 0.1, 0),
                        3, 0.18, 0.18, 0.18, 0.01);
            }
        }, () -> visualTasks.remove(condenserId, taskReference[0]), 1L, 1L);
        if (task == null) {
            return;
        }
        taskReference[0] = task;
        ScheduledTask previous = visualTasks.putIfAbsent(condenserId, task);
        if (previous != null) {
            task.cancel();
        }
    }

    private Component hologram(CondenserRecord condenser) {
        String color = switch (condenser.state()) {
            case ACTIVE -> "&a";
            case IDLE -> "&e";
            case DISABLED -> "&c";
        };
        return messages.parseComponent("&bCondensador de Materia &7[Nvl. " + condenser.level() + "]")
                .append(Component.newline())
                .append(messages.parseComponent("&7Estado: " + color + condenser.state().name()))
                .append(Component.newline())
                .append(messages.parseComponent("&7Consumo: &e"
                        + formatEnergy(energyCost(condenser)) + "⚡/generación"));
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

    private String formatEnergy(double amount) {
        return String.format(java.util.Locale.ROOT, "%.1f", amount);
    }

    public void flush() throws IOException {
        repository.flush();
    }
}
