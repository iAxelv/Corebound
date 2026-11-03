package com.corebound.thecore.incremental;

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
    private static final int UPGRADES_SLOT = 10;
    private static final int STATISTICS_SLOT = 4;
    private static final int CELL_FORGE_SLOT = 12;
    private static final int PYLON_SLOT = 14;
    private static final int UPGRADE_SLOT = 16;

    private final CoreService coreService;
    private final Messages messages;
    private final YamlConfiguration config;
    private PylonService pylonService;

    private static final class MenuHolder implements InventoryHolder {
        private final long islandIndex;
        private Inventory inventory;

        private MenuHolder(long islandIndex) {
            this.islandIndex = islandIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public CoreMenu(JavaPlugin plugin, Messages messages, CoreService coreService, File configFile) {
        this.coreService = coreService;
        this.messages = messages;
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultsStream = plugin.getResource("core/core_menu.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/core_menu.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not migrate core/core_menu.yml defaults.", exception);
        }
        if (config.getInt("rows", 3) != 3) {
            throw new IllegalArgumentException("core_menu.yml rows must be 3.");
        }
        validateItem("items.upgrades", UPGRADES_SLOT);
        validateItem("items.statistics", STATISTICS_SLOT);
        validateItem("items.pylon", PYLON_SLOT);
        validateItem("items.cell-forge", CELL_FORGE_SLOT);
        if (Material.matchMaterial(config.getString("items.pylon.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.pylon.locked-material must be valid.");
        }
        if (Material.matchMaterial(config.getString("items.cell-forge.locked-material", "BARRIER")) == null) {
            throw new IllegalArgumentException("core_menu.yml items.cell-forge.locked-material must be valid.");
        }
        validateItem("items.upgrade", UPGRADE_SLOT);
    }

    public void attachPylonService(PylonService pylonService) {
        this.pylonService = pylonService;
    }

    public void open(Player player, CoreRecord core) {
        MenuHolder holder = new MenuHolder(core.islandIndex());
        holder.inventory = Bukkit.createInventory(
                holder, 27, color(config.getString("title", "&5Gestión del Núcleo")));
        holder.inventory.setItem(UPGRADES_SLOT,
                createItem(config.getConfigurationSection("items.upgrades"), core, false));
        holder.inventory.setItem(STATISTICS_SLOT,
                createItem(config.getConfigurationSection("items.statistics"), core, false));
        holder.inventory.setItem(CELL_FORGE_SLOT, createCellForgeItem(core));
        holder.inventory.setItem(PYLON_SLOT, createPylonItem(core));
        holder.inventory.setItem(UPGRADE_SLOT, createItem(
                config.getConfigurationSection("items.upgrade"), core, core.energy() >= core.upgradeCost()));
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
        if (event.getRawSlot() == PYLON_SLOT && pylonService != null) {
            pylonService.buy(player, holder.islandIndex);
            coreService.openCoreMenu(player, holder.islandIndex);
        } else if (event.getRawSlot() == CELL_FORGE_SLOT && pylonService != null) {
            pylonService.openCellForge(player, holder.islandIndex);
        } else if (event.getRawSlot() == UPGRADE_SLOT && coreService.upgradeCore(player, holder.islandIndex)) {
            coreService.openCoreMenu(player, holder.islandIndex);
        } else if (event.getRawSlot() == UPGRADE_SLOT) {
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
        Map<String, String> placeholders = Map.of(
                "%level%", Integer.toString(core.level()),
                "%energy%", formatEnergy(core.energy()),
                "%max_energy%", formatEnergy(CoreRecord.MAX_ENERGY),
                "%base_energy%", formatEnergy(core.baseEnergy()),
                "%next_level%", Integer.toString(core.level() + 1),
                "%cost%", formatEnergy(core.upgradeCost()),
                "%next_base%", formatEnergy(Math.pow(1.25, core.level())));

        ItemStack item = new ItemStack(Material.matchMaterial(itemConfig.getString("material", "PAPER")));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(replace(itemConfig.getString("display-name", ""), placeholders)));
        meta.lore(lore.stream()
                .map(line -> color(replace(line, placeholders)))
                .toList());
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
                ? itemConfig.getString("display-name", "&bForjar Pilón I")
                : atLimit
                        ? itemConfig.getString("limit-name", "&cLímite de Pilones alcanzado")
                        : itemConfig.getString("locked-name", "&cPilón bloqueado");
        List<String> lore = itemConfig.getStringList(unlocked
                ? "lore"
                : atLimit ? "limit-lore" : "locked-lore");
        double cost = pylonService == null ? 150.0 : pylonService.purchaseCost();
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
        boolean unlocked = core.level() >= 3;
        String material = unlocked
                ? itemConfig.getString("material", "ENCHANTING_TABLE")
                : itemConfig.getString("locked-material", "BARRIER");
        String displayName = unlocked
                ? itemConfig.getString("display-name", "&5Forja Elemental")
                : itemConfig.getString("locked-name", "&cForja bloqueada");
        List<String> lore = itemConfig.getStringList(unlocked ? "lore" : "locked-lore");
        ItemStack item = new ItemStack(Material.matchMaterial(material));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(displayName));
        meta.lore(lore.stream().map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void validateItem(String path, int expectedSlot) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null
                || section.getInt("slot", -1) != expectedSlot
                || Material.matchMaterial(section.getString("material", "")) == null) {
            throw new IllegalArgumentException(
                    "core_menu.yml " + path + " must define a valid material and slot " + expectedSlot + ".");
        }
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
