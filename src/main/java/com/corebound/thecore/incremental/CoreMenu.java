package com.corebound.thecore.incremental;

import com.corebound.thecore.config.ConfigFiles;
import com.corebound.thecore.message.Messages;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class CoreMenu implements Listener {
    private final CoreService coreService;
    private final Messages messages;
    private final JavaPlugin plugin;
    private final File configFile;
    private YamlConfiguration config;
    private PylonService pylonService;
    private CondenserService condenserService;
    private CropGeneratorService cropGeneratorService;

    private static final class MenuHolder implements InventoryHolder {
        private final long islandIndex;
        private final Map<String, Integer> slots;
        private Inventory inventory;

        private MenuHolder(long islandIndex, Map<String, Integer> slots) {
            this.islandIndex = islandIndex;
            this.slots = Map.copyOf(slots);
        }

        private int slot(String item) {
            return slots.get(item);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public CoreMenu(JavaPlugin plugin, Messages messages, CoreService coreService, File configFile) {
        this.plugin = plugin;
        this.coreService = coreService;
        this.messages = messages;
        this.configFile = configFile;
        reload(true);
    }

    public void reload() {
        reload(false);
    }

    private void reload(boolean saveDefaults) {
        YamlConfiguration loaded = ConfigFiles.load(configFile);
        InputStream defaultsStream = plugin.getResource("core/core_menu.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/core_menu.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/core_menu.yml defaults.", exception);
        }
        int rows = loaded.getInt("rows", 6);
        if (rows == 5) {
            loaded.set("rows", 6);
            try {
                loaded.save(configFile);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Could not migrate core/core_menu.yml to six rows.", exception);
            }
        } else if (rows != 6) {
            throw new IllegalArgumentException("core_menu.yml rows must be 6.");
        }
        validateItem(loaded, "items.upgrades");
        validateItem(loaded, "items.statistics");
        validateItem(loaded, "items.fracture");
        validateItem(loaded, "items.astral-tree");
        validateItem(loaded, "items.pylon");
        validateItem(loaded, "items.condenser");
        validateItem(loaded, "items.crop-generator");
        validateItem(loaded, "items.cell-forge");
        validateItem(loaded, "items.upgrade");
        validateItem(loaded, "items.close");
        if (Material.matchMaterial(loaded.getString("items.pylon.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.pylon.locked-material must be valid.");
        }
        if (Material.matchMaterial(loaded.getString("items.fracture.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.fracture.locked-material must be valid.");
        }
        if (Material.matchMaterial(loaded.getString("items.cell-forge.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.cell-forge.locked-material must be valid.");
        }
        if (Material.matchMaterial(loaded.getString("items.condenser.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.condenser.locked-material must be valid.");
        }
        if (Material.matchMaterial(loaded.getString("items.condenser.level-locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.condenser.level-locked-material must be valid.");
        }
        if (Material.matchMaterial(loaded.getString("items.crop-generator.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.crop-generator.locked-material must be valid.");
        }
        config = loaded;
    }

    public void attachPylonService(PylonService pylonService) {
        this.pylonService = pylonService;
    }

    public void attachCondenserService(CondenserService condenserService) {
        this.condenserService = condenserService;
    }

    public void attachCropGeneratorService(CropGeneratorService cropGeneratorService) {
        this.cropGeneratorService = cropGeneratorService;
    }

    public void open(Player player, CoreRecord core) {
        MenuHolder holder = new MenuHolder(core.islandIndex(), menuSlots());
        holder.inventory = Bukkit.createInventory(
                holder, 54, color(config.getString("title")));
        holder.inventory.setItem(slot("upgrades"),
                createItem(config.getConfigurationSection("items.upgrades"), core, false));
        holder.inventory.setItem(slot("statistics"),
                createItem(config.getConfigurationSection("items.statistics"), core, false));
        holder.inventory.setItem(slot("astral-tree"), createSimpleItem("items.astral-tree"));
        holder.inventory.setItem(slot("cell-forge"), createCellForgeItem(core));
        holder.inventory.setItem(slot("fracture"), createFractureItem(core));
        holder.inventory.setItem(slot("pylon"), createPylonItem(core));
        holder.inventory.setItem(slot("condenser"), createCondenserItem(core));
        holder.inventory.setItem(slot("crop-generator"), createCropGeneratorItem(core));
        holder.inventory.setItem(slot("upgrade"), createItem(
                config.getConfigurationSection("items.upgrade"), core, core.energy() >= core.upgradeCost()));
        holder.inventory.setItem(slot("close"), createSimpleItem("items.close"));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() == holder.slot("upgrades")) {
            coreService.openCoreUpgrades(player, holder.islandIndex);
        } else if (event.getRawSlot() == holder.slot("pylon") && pylonService != null) {
            pylonService.buy(player, holder.islandIndex);
            coreService.openCoreMenu(player, holder.islandIndex);
        } else if (event.getRawSlot() == holder.slot("cell-forge") && pylonService != null) {
            pylonService.openCellForge(player, holder.islandIndex);
        } else if (event.getRawSlot() == holder.slot("condenser") && condenserService != null) {
            if (condenserService.buy(player, holder.islandIndex)) {
                coreService.openCoreMenu(player, holder.islandIndex);
            }
        } else if (event.getRawSlot() == holder.slot("crop-generator") && cropGeneratorService != null) {
            if (cropGeneratorService.buy(player, holder.islandIndex)) {
                coreService.openCoreMenu(player, holder.islandIndex);
            }
        } else if (event.getRawSlot() == holder.slot("astral-tree")) {
            coreService.openAstralTree(player, holder.islandIndex);
        } else if (event.getRawSlot() == holder.slot("fracture")) {
            if (coreService.fractureCore(player, holder.islandIndex)) {
                coreService.openCoreMenu(player, holder.islandIndex);
            }
        } else if (event.getRawSlot() == holder.slot("close")) {
            player.closeInventory();
        } else if (event.getRawSlot() == holder.slot("upgrade")
                && coreService.upgradeCore(player, holder.islandIndex)) {
            coreService.openCoreMenu(player, holder.islandIndex);
        } else if (event.getRawSlot() == holder.slot("upgrade")) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack createItem(ConfigurationSection itemConfig, CoreRecord core, boolean canUpgrade) {
        List<String> lore;
        if (itemConfig.contains("lore")) {
            lore = itemConfig.getStringList("lore");
        } else {
            String path = canUpgrade ? "lore-enough" : "lore-insufficient";
            lore = itemConfig.getStringList(path);
        }
        Map<String, String> placeholders = Map.ofEntries(
                Map.entry("%level%", Integer.toString(core.level())),
                Map.entry("%prestige%", Integer.toString(core.prestigeCount())),
                Map.entry("%fragments%", Integer.toString(core.coreFragments())),
                Map.entry("%prestige_bonus%", String.format(
                        java.util.Locale.ROOT, "%.0f", (core.prestigeMultiplier() - 1.0) * 100.0)),
                Map.entry("%energy%", formatEnergy(core.energy())),
                Map.entry("%max_energy%", formatEnergy(core.maxEnergy())),
                Map.entry("%base_energy%", formatEnergy(core.baseEnergy())),
                Map.entry("%max_combo%", String.format(java.util.Locale.ROOT, "%.1f", core.maxCombo())),
                Map.entry("%next_level%", Integer.toString(core.level() + 1)),
                Map.entry("%cost%", formatEnergy(core.upgradeCost())),
                Map.entry("%next_base%", formatEnergy(Math.pow(1.25, core.level()))),
                Map.entry("%capacity_bonus%", Integer.toString(core.upgradeCapacity() * 12)));

        ItemStack item = new ItemStack(Material.matchMaterial(itemConfig.getString("material", "PAPER")));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(replace(itemConfig.getString("display-name", ""), placeholders)));
        meta.lore(lore.stream()
                .map(line -> color(replace(line, placeholders)))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFractureItem(CoreRecord core) {
        ConfigurationSection itemConfig = config.getConfigurationSection("items.fracture");
        boolean unlocked = core.level() >= 10;
        String material = itemConfig.getString(unlocked ? "material" : "locked-material",
                unlocked ? "NETHER_STAR" : "BARRIER");
        ItemStack item = new ItemStack(Material.matchMaterial(material));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(itemConfig.getString(unlocked ? "display-name" : "locked-name")));
        meta.lore(itemConfig.getStringList(unlocked ? "lore" : "locked-lore").stream()
                .map(line -> color(replace(line, Map.of(
                        "%level%", Integer.toString(core.level()),
                        "%prestige%", Integer.toString(core.prestigeCount()),
                        "%fragments%", Integer.toString(core.coreFragments())))))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSimpleItem(String path) {
        ConfigurationSection itemConfig = config.getConfigurationSection(path);
        ItemStack item = new ItemStack(Material.matchMaterial(itemConfig.getString("material", "PAPER")));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(itemConfig.getString("display-name", "")));
        meta.lore(itemConfig.getStringList("lore").stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPylonItem(CoreRecord core) {
        ConfigurationSection itemConfig = config.getConfigurationSection("items.pylon");
        boolean levelUnlocked = core.level() >= 3;
        boolean hasCapacity = pylonService == null
                || pylonService.hasPylonCapacity(core.islandIndex(), core.pylonCapacity());
        boolean unlocked = levelUnlocked && hasCapacity;
        boolean atLimit = levelUnlocked && !hasCapacity;
        String material = unlocked
                ? itemConfig.getString("material", "SEA_LANTERN")
                : itemConfig.getString("locked-material", "BARRIER");
        String displayName = unlocked
                ? itemConfig.getString("display-name")
                : atLimit
                        ? itemConfig.getString("limit-name")
                        : itemConfig.getString("locked-name");
        List<String> lore = itemConfig.getStringList(unlocked
                ? "lore"
                : atLimit ? "limit-lore" : "locked-lore");
        double cost = pylonService == null ? 500.0 : pylonService.nextPurchaseCost(core.islandIndex());
        Map<String, String> placeholders = Map.of(
                "%level%", Integer.toString(core.level()),
                "%cost%", formatEnergy(cost),
                "%pylons%", Integer.toString(core.pylonCapacity()),
                "%owned%", Integer.toString(pylonService == null
                        ? 0
                        : pylonService.ownedPylonCount(core.islandIndex())));
        ItemStack item = new ItemStack(Material.matchMaterial(material));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(replace(displayName, placeholders)));
        meta.lore(lore.stream()
                .map(line -> color(replace(line, placeholders)))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCellForgeItem(CoreRecord core) {
        ConfigurationSection itemConfig = config.getConfigurationSection("items.cell-forge");
        boolean unlocked = core.level() >= 5;
        String material = unlocked
                ? itemConfig.getString("material", "ENCHANTING_TABLE")
                : itemConfig.getString("locked-material", "BARRIER");
        String displayName = unlocked
                ? itemConfig.getString("display-name")
                : itemConfig.getString("locked-name");
        List<String> lore = itemConfig.getStringList(unlocked ? "lore" : "locked-lore");
        ItemStack item = new ItemStack(Material.matchMaterial(material));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(displayName));
        meta.lore(lore.stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCondenserItem(CoreRecord core) {
        ConfigurationSection itemConfig = config.getConfigurationSection("items.condenser");
        int requiredLevel = condenserService == null ? 3 : condenserService.minimumCoreLevel();
        boolean levelLocked = core.level() < requiredLevel;
        boolean available = condenserService != null && !levelLocked;
        String material = available
                ? itemConfig.getString("material", "BLAST_FURNACE")
                : itemConfig.getString("level-locked-material", "BARRIER");
        String displayName = available
                ? itemConfig.getString("display-name")
                : itemConfig.getString("level-locked-name");
        List<String> lore = itemConfig.getStringList(
                available ? "lore" : "level-locked-lore");
        lore = lore.stream()
                .map(line -> line.replace("%required_level%", Integer.toString(requiredLevel)))
                .toList();
        double cost = condenserService == null ? 500.0 : condenserService.purchaseCost();
        Map<String, String> placeholders = Map.of(
                "%cost%", formatEnergy(cost),
                "%min_distance%", formatEnergy(pylonService == null ? 3.0 : pylonService.minimumDistance()),
                "%max_distance%", formatEnergy(pylonService == null ? 12.0 : pylonService.maximumDistance()));
        ItemStack item = new ItemStack(Material.matchMaterial(material));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(replace(displayName, placeholders)));
        meta.lore(lore.stream().map(line -> color(replace(line, placeholders))).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCropGeneratorItem(CoreRecord core) {
        ConfigurationSection itemConfig = config.getConfigurationSection("items.crop-generator");
        int requiredLevel = cropGeneratorService == null ? 5 : cropGeneratorService.minimumCoreLevel();
        boolean unlocked = cropGeneratorService != null && core.level() >= requiredLevel;
        String material = itemConfig.getString(unlocked ? "material" : "locked-material",
                unlocked ? "COMPOSTER" : "BARRIER");
        String displayName = itemConfig.getString(unlocked ? "display-name" : "locked-name");
        List<String> lore = itemConfig.getStringList(unlocked ? "lore" : "locked-lore");
        Map<String, String> placeholders = Map.of(
                "%required_level%", Integer.toString(requiredLevel),
                "%cost%", formatEnergy(cropGeneratorService == null ? 750.0 : cropGeneratorService.purchaseCost()),
                "%min_distance%", formatEnergy(pylonService == null ? 3.0 : pylonService.minimumDistance()),
                "%max_distance%", formatEnergy(pylonService == null ? 12.0 : pylonService.maximumDistance()));
        ItemStack item = new ItemStack(Material.matchMaterial(material));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(replace(displayName, placeholders)));
        meta.lore(lore.stream().map(line -> color(replace(line, placeholders))).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void validateItem(YamlConfiguration loaded, String path) {
        ConfigurationSection section = loaded.getConfigurationSection(path);
        int slot = section == null ? -1 : section.getInt("slot", -1);
        if (section == null
                || slot < 0
                || slot >= loaded.getInt("rows", 5) * 9
                || Material.matchMaterial(section.getString("material", "")) == null) {
            throw new IllegalArgumentException("core_menu.yml " + path + " must define a valid material and slot.");
        }
        for (String otherPath : List.of(
                "items.upgrades", "items.statistics", "items.fracture", "items.astral-tree", "items.pylon",
                "items.condenser", "items.crop-generator", "items.cell-forge", "items.upgrade", "items.close")) {
            if (!otherPath.equals(path)
                    && loaded.getInt(otherPath + ".slot", -1) == slot) {
                throw new IllegalArgumentException("core_menu.yml item slots must be unique.");
            }
        }
    }

    private int slot(String item) {
        return config.getInt("items." + item + ".slot");
    }

    private Map<String, Integer> menuSlots() {
        return Map.of(
                "upgrades", slot("upgrades"),
                "statistics", slot("statistics"),
                "fracture", slot("fracture"),
                "astral-tree", slot("astral-tree"),
                "pylon", slot("pylon"),
                "condenser", slot("condenser"),
                "crop-generator", slot("crop-generator"),
                "cell-forge", slot("cell-forge"),
                "upgrade", slot("upgrade"),
                "close", slot("close"));
    }

    private String replace(String text, Map<String, String> placeholders) {
        String result = text;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return result;
    }

    private String formatEnergy(double energy) {
        if (energy >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", energy / 1_000_000);
        }
        if (energy >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", energy / 1_000);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", energy);
    }

    private Component color(String text) {
        return messages.parseComponent(text);
    }
}
