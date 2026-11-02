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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class PylonService {
    private static final int TIER_ONE = 1;

    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final CoreService coreService;
    private final CoreRepository coreRepository;
    private final PylonRepository repository;
    private final Messages messages;
    private final YamlConfiguration config;
    private final NamespacedKey pylonItemKey;
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
    private final Sound pulseSound;
    private final Set<UUID> activePylons = ConcurrentHashMap.newKeySet();

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
        String soundKey = config.getString("pylon.pulse-sound", "minecraft:block.amethyst_block.chime");
        NamespacedKey parsedSound = soundKey == null ? null : NamespacedKey.fromString(soundKey);
        pulseSound = parsedSound == null ? null : Registry.SOUNDS.get(parsedSound);
        if (pulseSound == null) {
            throw new IllegalArgumentException("pylons.yml pylon.pulse-sound must be a registered sound.");
        }
        pylonItemKey = new NamespacedKey(plugin, "pylon_item");
        pylonEntityKey = new NamespacedKey(plugin, "pylon_entity");
        pylonIdKey = new NamespacedKey(plugin, "pylon_id");
    }

    public double purchaseCost() {
        return purchaseCost;
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
        if (core.level() < 2) {
            messages.send(player, "pylon-requires-core-level");
            return false;
        }
        CoreRecord chargedCore = coreRepository.spendEnergy(islandIndex, purchaseCost);
        if (chargedCore == null) {
            messages.send(player, "pylon-insufficient-energy");
            return false;
        }
        ItemStack item = createPylonItem();
        try {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                dropped.setPersistent(true);
            }
        } catch (RuntimeException exception) {
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
        if (core.level() < 2) {
            messages.send(player, "pylon-requires-core-level");
            return false;
        }
        if (repository.forIsland(island.index()).size() >= core.pylonCapacity()) {
            messages.send(player, "pylon-limit-reached");
            return false;
        }

        Location anchor = new Location(
                clickedBlock.getWorld(),
                clickedBlock.getX() + 0.5,
                clickedBlock.getY() + 1.0,
                clickedBlock.getZ() + 0.5);
        Location coreLocation = new Location(
                clickedBlock.getWorld(), core.x(), core.y(), core.z());
        double distance = anchor.distance(coreLocation);
        if (distance < minimumDistance || distance > maximumDistance) {
            messages.send(player, "pylon-distance-invalid", Map.of(
                    "min", formatEnergy(minimumDistance),
                    "max", formatEnergy(maximumDistance)));
            return false;
        }

        UUID pylonId = UUID.randomUUID();
        ItemDisplay baseDisplay = null;
        ItemDisplay rodDisplay = null;
        Interaction interaction = null;
        try {
            baseDisplay = clickedBlock.getWorld().spawn(anchor, ItemDisplay.class, display -> {
                configureDisplay(display, baseMaterial, 0.75f, island.index(), pylonId);
            });
            Location rodLocation = anchor.clone().add(0, 0.85, 0);
            rodDisplay = clickedBlock.getWorld().spawn(rodLocation, ItemDisplay.class, display -> {
                configureDisplay(display, rodMaterial, displayScale, island.index(), pylonId);
            });
            Location interactionLocation = anchor.clone().add(0, 0.8, 0);
            interaction = clickedBlock.getWorld().spawn(interactionLocation, Interaction.class, entity -> {
                entity.setInteractionWidth(1.0f);
                entity.setInteractionHeight(2.0f);
                entity.setResponsive(true);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(true);
                markEntity(entity, island.index(), pylonId);
            });
            repository.add(new PylonRecord(
                    island.index(),
                    pylonId,
                    clickedBlock.getWorld().getUID(),
                    anchor.getX(),
                    anchor.getY(),
                    anchor.getZ(),
                    baseDisplay.getUniqueId(),
                    rodDisplay.getUniqueId(),
                    interaction.getUniqueId(),
                    TIER_ONE));
        } catch (RuntimeException exception) {
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
                    startPulse(interaction);
                }
            });
        }
    }

    private void startPulse(Interaction interaction) {
        UUID pylonId = interaction.getPersistentDataContainer().get(pylonIdKey, PersistentDataType.STRING) == null
                ? null
                : UUID.fromString(interaction.getPersistentDataContainer().get(pylonIdKey, PersistentDataType.STRING));
        if (pylonId == null || repository.get(pylonId) == null || !activePylons.add(pylonId)) {
            return;
        }
        interaction.getScheduler().runAtFixedRate(plugin, task -> {
            if (!interaction.isValid()) {
                task.cancel();
                activePylons.remove(pylonId);
                return;
            }
            firePulse(interaction, pylonId);
        }, () -> activePylons.remove(pylonId), intervalTicks, intervalTicks);
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
        Location source = interaction.getLocation().add(0, 0.8, 0);
        Location target = new Location(world, core.x(), core.y(), core.z());
        drawPulseLine(world, source, target);
        long now = System.currentTimeMillis();
        CoreRecord updated = coreService.addPassiveEnergy(pylon.islandIndex(), baseYieldRatio, now);
        if (updated == null) {
            return;
        }
        Location soundLocation = target.clone();
        plugin.getServer().getRegionScheduler().run(plugin, soundLocation, task ->
                world.playSound(soundLocation, pulseSound, 0.8f, 1.1f));
    }

    private void drawPulseLine(World world, Location source, Location target) {
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
            plugin.getServer().getRegionScheduler().run(plugin, particleLocation, task ->
                    world.spawnParticle(Particle.ELECTRIC_SPARK, particleLocation, 2, 0.025, 0.025, 0.025, 0.01));
        }
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

    private void markEntity(org.bukkit.entity.Entity entity, long islandIndex, UUID pylonId) {
        entity.getPersistentDataContainer().set(pylonEntityKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "pylon_island_index"), PersistentDataType.LONG, islandIndex);
        entity.getPersistentDataContainer().set(pylonIdKey, PersistentDataType.STRING, pylonId.toString());
    }

    private ItemStack createPylonItem() {
        ItemStack item = new ItemStack(itemMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(config.getString(
                "pylon.item.display-name", "&bPilón de Resonancia")));
        meta.lore(config.getStringList("pylon.item.lore").stream()
                .map(messages::parseComponent)
                .toList());
        meta.getPersistentDataContainer().set(pylonItemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "pylon_tier"), PersistentDataType.INTEGER, TIER_ONE);
        item.setItemMeta(meta);
        return item;
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

    public void flush() throws IOException {
        repository.flush();
    }
}
