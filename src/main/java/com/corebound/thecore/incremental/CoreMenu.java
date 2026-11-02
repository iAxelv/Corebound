package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.io.File;
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

public final class CoreMenu implements Listener {
    private static final int UPGRADE_SLOT = 15;

    private final CoreService coreService;
    private final Messages messages;
    private final YamlConfiguration config;

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

    public CoreMenu(Messages messages, CoreService coreService, File configFile) {
        this.coreService = coreService;
        this.messages = messages;
        config = YamlConfiguration.loadConfiguration(configFile);
        if (config.getInt("rows", 3) != 3) {
            throw new IllegalArgumentException("core_menu.yml rows must be 3.");
        }
        validateItem("items.statistics", 11);
        validateItem("items.upgrade", UPGRADE_SLOT);
    }

    public void open(Player player, CoreRecord core) {
        MenuHolder holder = new MenuHolder(core.islandIndex());
        holder.inventory = Bukkit.createInventory(
                holder, 27, color(config.getString("title", "&5Gestión del Núcleo")));
        holder.inventory.setItem(11, createItem(config.getConfigurationSection("items.statistics"), core, false));
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
                || event.getRawSlot() != UPGRADE_SLOT
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (coreService.upgradeCore(player, holder.islandIndex)) {
            coreService.openCoreMenu(player, holder.islandIndex);
        } else {
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
