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

public final class CoreUpgradesMenu implements Listener {
    private static final int BACK_SLOT = 22;
    private static final Map<CoreEnhancement, Integer> SLOTS = Map.of(
            CoreEnhancement.OVERCHARGE, 11,
            CoreEnhancement.WINDOW, 13,
            CoreEnhancement.CRITICAL, 15);

    private static final class Holder implements InventoryHolder {
        private final long islandIndex;
        private Inventory inventory;

        private Holder(long islandIndex) {
            this.islandIndex = islandIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Messages messages;
    private final CoreService cores;
    private final YamlConfiguration config;

    public CoreUpgradesMenu(JavaPlugin plugin, Messages messages, CoreService cores, File configFile) {
        this.messages = messages;
        this.cores = cores;
        config = YamlConfiguration.loadConfiguration(configFile);
        InputStream defaultsStream = plugin.getResource("core/core_upgrades.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/core_upgrades.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            config.options().copyDefaults(true);
            config.save(configFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not migrate core/core_upgrades.yml defaults.", exception);
        }
        if (config.getInt("rows", 3) != 3) {
            throw new IllegalArgumentException("core_upgrades.yml rows must be 3.");
        }
        for (CoreEnhancement enhancement : CoreEnhancement.values()) {
            validateUpgrade(enhancement);
        }
        ConfigurationSection back = config.getConfigurationSection("items.back");
        if (back == null
                || back.getInt("slot", -1) != BACK_SLOT
                || Material.matchMaterial(back.getString("material", "")) == null) {
            throw new IllegalArgumentException("core_upgrades.yml items.back must define a valid material and slot 22.");
        }
    }

    public void open(Player player, long islandIndex) {
        CoreRecord core = cores.getCoreForMenu(player, islandIndex);
        if (core == null) {
            return;
        }
        Holder holder = new Holder(islandIndex);
        holder.inventory = Bukkit.createInventory(
                holder, 27, messages.parseComponent(config.getString("title", "&5Mejoras del Núcleo")));
        for (CoreEnhancement enhancement : CoreEnhancement.values()) {
            holder.inventory.setItem(SLOTS.get(enhancement), upgradeItem(core, enhancement));
        }
        holder.inventory.setItem(BACK_SLOT, backItem());
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == BACK_SLOT) {
            cores.openCoreMenu(player, holder.islandIndex);
            return;
        }
        for (CoreEnhancement enhancement : CoreEnhancement.values()) {
            if (slot == SLOTS.get(enhancement)) {
                ConfigurationSection item = config.getConfigurationSection("items." + path(enhancement));
                CoreRecord core = cores.getCoreForMenu(player, holder.islandIndex);
                if (core == null) {
                    player.closeInventory();
                    return;
                }
                int currentLevel = core.enhancementLevel(enhancement);
                double cost = item.getDouble("base-cost") * Math.pow(item.getDouble("cost-multiplier"), currentLevel);
                cores.upgradeEnhancement(
                        player, holder.islandIndex, enhancement, cost, item.getInt("max-level"));
                open(player, holder.islandIndex);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private ItemStack upgradeItem(CoreRecord core, CoreEnhancement enhancement) {
        ConfigurationSection item = config.getConfigurationSection("items." + path(enhancement));
        int level = core.enhancementLevel(enhancement);
        int maxLevel = item.getInt("max-level");
        double cost = item.getDouble("base-cost") * Math.pow(item.getDouble("cost-multiplier"), level);
        String status = level >= maxLevel
                ? "&aNivel máximo alcanzado."
                : core.energy() >= cost ? "&eHaz clic para mejorar." : "&cEnergía insuficiente.";
        String title = replace(item.getString("display-name", ""), Map.of(
                "%level%", Integer.toString(level),
                "%max%", Integer.toString(maxLevel)));
        List<Component> lore = item.getStringList("lore").stream()
                .map(line -> replace(line, Map.of(
                        "%level%", Integer.toString(level),
                        "%max%", Integer.toString(maxLevel),
                        "%cost%", formatEnergy(cost),
                        "%chance%", Integer.toString(level * 5),
                        "%status%", status)))
                .map(messages::parseComponent)
                .toList();
        ItemStack stack = new ItemStack(Material.matchMaterial(item.getString("material")));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(messages.parseComponent(title));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack backItem() {
        ConfigurationSection item = config.getConfigurationSection("items.back");
        ItemStack stack = new ItemStack(Material.matchMaterial(item.getString("material")));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(messages.parseComponent(item.getString("display-name", "&cVolver al Menú Principal")));
        stack.setItemMeta(meta);
        return stack;
    }

    private void validateUpgrade(CoreEnhancement enhancement) {
        String path = "items." + path(enhancement);
        ConfigurationSection item = config.getConfigurationSection(path);
        int expectedSlot = SLOTS.get(enhancement);
        if (item == null
                || item.getInt("slot", -1) != expectedSlot
                || Material.matchMaterial(item.getString("material", "")) == null
                || item.getInt("max-level", 0) <= 0
                || !Double.isFinite(item.getDouble("base-cost", Double.NaN))
                || item.getDouble("base-cost", 0) <= 0
                || !Double.isFinite(item.getDouble("cost-multiplier", Double.NaN))
                || item.getDouble("cost-multiplier", 0) < 1
                || item.getInt("max-level", 0) > 5
                || item.getStringList("lore").isEmpty()) {
            throw new IllegalArgumentException("core_upgrades.yml " + path + " contains invalid upgrade settings.");
        }
    }

    private String path(CoreEnhancement enhancement) {
        return enhancement.name().toLowerCase(java.util.Locale.ROOT);
    }

    private String replace(String text, Map<String, String> placeholders) {
        String result = text;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return result;
    }

    private String formatEnergy(double energy) {
        return String.format(java.util.Locale.ROOT, "%.1f", energy);
    }
}
