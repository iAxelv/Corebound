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

public final class CoreUpgradesMenu implements Listener {
    private static final int BACK_SLOT = 40;
    private static final int INVENTORY_SIZE = 45;
    private static final Map<CoreEnhancement, Integer> SLOTS = Map.of(
            CoreEnhancement.OVERCHARGE, 20,
            CoreEnhancement.CRITICAL, 21,
            CoreEnhancement.CAPACITY, 22,
            CoreEnhancement.HIT_YIELD, 23);

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
    private final JavaPlugin plugin;
    private final File configFile;
    private YamlConfiguration config;

    public CoreUpgradesMenu(JavaPlugin plugin, Messages messages, CoreService cores, File configFile) {
        this.plugin = plugin;
        this.messages = messages;
        this.cores = cores;
        this.configFile = configFile;
        reload(true);
    }

    public void reload() {
        reload(false);
    }

    private void reload(boolean saveDefaults) {
        YamlConfiguration loaded = ConfigFiles.load(configFile);
        InputStream defaultsStream = plugin.getResource("core/core_upgrades.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/core_upgrades.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/core_upgrades.yml defaults.", exception);
        }
        if (Material.matchMaterial(loaded.getString("panels.material", "")) == null) {
            throw new IllegalArgumentException("core_upgrades.yml panels.material must be valid.");
        }
        for (CoreEnhancement enhancement : CoreEnhancement.values()) {
            validateUpgrade(loaded, enhancement);
        }
        ConfigurationSection back = loaded.getConfigurationSection("items.back");
        if (back == null
                || Material.matchMaterial(back.getString("material", "")) == null) {
            throw new IllegalArgumentException("core_upgrades.yml items.back must define a valid material.");
        }
        config = loaded;
    }

    public void open(Player player, long islandIndex) {
        CoreRecord core = cores.getCoreForMenu(player, islandIndex);
        if (core == null) {
            return;
        }
        Holder holder = new Holder(islandIndex);
        holder.inventory = Bukkit.createInventory(
                holder, INVENTORY_SIZE, messages.parseComponent(config.getString("title")));
        fill(holder.inventory);
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
                int maxLevel = item.getInt("max-level");
                if (currentLevel >= maxLevel) {
                    cores.upgradeEnhancement(player, holder.islandIndex, enhancement, 0, maxLevel, 1);
                    open(player, holder.islandIndex);
                    return;
                }
                int requiredCoreLevel = item.getIntegerList("required-core-levels").get(currentLevel);
                double cost = item.getDouble("base-cost")
                        * Math.pow(item.getDouble("cost-multiplier"), currentLevel);
                cores.upgradeEnhancement(
                        player, holder.islandIndex, enhancement, cost, maxLevel, requiredCoreLevel);
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
        boolean maxed = level >= maxLevel;
        int requiredCoreLevel = maxed
                ? core.level()
                : item.getIntegerList("required-core-levels").get(level);
        double cost = item.getDouble("base-cost") * Math.pow(item.getDouble("cost-multiplier"), level);
        boolean coreLevelLocked = !maxed && core.level() < requiredCoreLevel;
        String statusPath = maxed
                ? "status.max-level"
                : coreLevelLocked
                        ? "status.level-locked"
                        : core.energy() < cost
                                ? "status.insufficient-energy"
                                : "status.can-upgrade";
        String status = replace(config.getString(statusPath, ""), Map.of(
                "%required_level%", Integer.toString(requiredCoreLevel),
                "%cost%", formatEnergy(cost)));
        String title = replace(item.getString("display-name", ""), Map.of(
                "%level%", Integer.toString(level),
                "%max%", Integer.toString(maxLevel)));
        List<Component> lore = item.getStringList("lore").stream()
                .map(line -> replace(line, Map.ofEntries(
                        Map.entry("%level%", Integer.toString(level)),
                        Map.entry("%max%", Integer.toString(maxLevel)),
                        Map.entry("%cost%", formatEnergy(cost)),
                        Map.entry("%chance%", Integer.toString((int) Math.round(core.criticalChance() * 100))),
                        Map.entry("%capacity%", Integer.toString(core.upgradeCapacity() * 12)),
                        Map.entry("%combo%", String.format(java.util.Locale.ROOT, "%.2f", core.maxCombo())),
                        Map.entry("%yield%", Integer.toString(core.upgradeHitYield() * 8)),
                        Map.entry("%required_level%", Integer.toString(requiredCoreLevel)),
                        Map.entry("%status%", status))))
                .map(messages::parseComponent)
                .toList();
        Material material = Material.matchMaterial(coreLevelLocked
                ? item.getString("locked-material", "BARRIER")
                : item.getString("material", ""));
        ItemStack stack = new ItemStack(material);
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
        meta.displayName(messages.parseComponent(item.getString("display-name")));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack panelItem() {
        ItemStack pane = new ItemStack(Material.matchMaterial(config.getString("panels.material")));
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        return pane;
    }

    private void fill(Inventory inventory) {
        ItemStack pane = panelItem();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot < 9 || slot >= 36 || slot % 9 == 0 || slot % 9 == 8) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private void validateUpgrade(YamlConfiguration loaded, CoreEnhancement enhancement) {
        String path = "items." + path(enhancement);
        ConfigurationSection item = loaded.getConfigurationSection(path);
        List<Integer> requiredLevels = item == null ? List.of() : item.getIntegerList("required-core-levels");
        int previousRequiredLevel = 0;
        boolean validRequirements = item != null
                && requiredLevels.size() == item.getInt("max-level", 0);
        for (int requiredLevel : requiredLevels) {
            validRequirements &= requiredLevel >= previousRequiredLevel && requiredLevel >= 1;
            previousRequiredLevel = requiredLevel;
        }
        if (item == null
                || Material.matchMaterial(item.getString("material", "")) == null
                || Material.matchMaterial(item.getString("locked-material", "BARRIER")) == null
                || item.getInt("max-level", 0) <= 0
                || !Double.isFinite(item.getDouble("base-cost", Double.NaN))
                || item.getDouble("base-cost", 0) <= 0
                || !Double.isFinite(item.getDouble("cost-multiplier", Double.NaN))
                || item.getDouble("cost-multiplier", 0) < 1
                || item.getInt("max-level", 0) > 5
                || item.getStringList("lore").isEmpty()
                || !validRequirements) {
            throw new IllegalArgumentException("core_upgrades.yml " + path + " contains invalid upgrade settings.");
        }
    }

    private String path(CoreEnhancement enhancement) {
        return switch (enhancement) {
            case OVERCHARGE -> "overcharge";
            case CRITICAL -> "critical";
            case CAPACITY -> "capacity";
            case HIT_YIELD -> "hit-yield";
        };
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
