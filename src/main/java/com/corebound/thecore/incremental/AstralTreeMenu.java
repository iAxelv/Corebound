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

public final class AstralTreeMenu implements Listener {
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 49;

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

    private final JavaPlugin plugin;
    private final Messages messages;
    private final CoreService cores;
    private final File configFile;
    private YamlConfiguration config;

    public AstralTreeMenu(JavaPlugin plugin, Messages messages, CoreService cores, File configFile) {
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
        InputStream defaultsStream = plugin.getResource("core/astral_tree.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled core/astral_tree.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            if (saveDefaults) {
                loaded.options().copyDefaults(true);
                loaded.save(configFile);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load core/astral_tree.yml defaults.", exception);
        }
        if (loaded.getInt("rows", 6) != 6) {
            throw new IllegalArgumentException("astral_tree.yml rows must be 6.");
        }
        validateItem(loaded, "items.info", INFO_SLOT);
        validateItem(loaded, "items.resonance", -1);
        validateItem(loaded, "items.pylon-attunement", -1);
        validateItem(loaded, "items.back", BACK_SLOT);
        List<Integer> occupiedSlots = List.of(
                loaded.getInt("items.info.slot"),
                loaded.getInt("items.resonance.slot"),
                loaded.getInt("items.pylon-attunement.slot"),
                loaded.getInt("items.back.slot"));
        if (occupiedSlots.stream().distinct().count() != occupiedSlots.size()) {
            throw new IllegalArgumentException("astral_tree.yml item slots must be unique.");
        }
        config = loaded;
    }

    public void open(Player player, CoreRecord core) {
        Holder holder = new Holder(core.islandIndex());
        holder.inventory = Bukkit.createInventory(
                holder, 54, messages.parseComponent(config.getString("title", "&5Árbol de Fracturas")));
        holder.inventory.setItem(slot("info"), infoItem(core));
        holder.inventory.setItem(slot("back"), simpleItem("items.back", Map.of()));
        holder.inventory.setItem(slot("resonance"), skillItem(core, AstralSkill.RESONANCE));
        holder.inventory.setItem(slot("pylon-attunement"), skillItem(core, AstralSkill.PYLON_ATTUNEMENT));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == slot("back")) {
            cores.openCoreMenu(player, holder.islandIndex);
            return;
        }
        AstralSkill skill = slot == slot("resonance")
                ? AstralSkill.RESONANCE
                : slot == slot("pylon-attunement")
                        ? AstralSkill.PYLON_ATTUNEMENT
                        : null;
        if (skill != null) {
            cores.upgradeAstralSkill(player, holder.islandIndex, skill);
            CoreRecord updated = cores.getCoreForMenu(player, holder.islandIndex);
            if (updated != null) {
                open(player, updated);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private ItemStack infoItem(CoreRecord core) {
        return simpleItem("items.info", Map.of(
                "%fragments%", Integer.toString(core.coreFragments()),
                "%prestige%", Integer.toString(core.prestigeCount())));
    }

    private ItemStack skillItem(CoreRecord core, AstralSkill skill) {
        String path = skill == AstralSkill.RESONANCE ? "items.resonance" : "items.pylon-attunement";
        ConfigurationSection item = config.getConfigurationSection(path);
        int level = core.astralSkillLevel(skill);
        int nextLevel = level + 1;
        boolean maxed = level >= skill.maxLevel();
        int requiredFracture = maxed ? 0 : skill.fractureRequirement(nextLevel);
        int cost = maxed ? 0 : skill.fragmentCost(nextLevel);
        String status = maxed
                ? item.getString("maxed-status", "&aNivel máximo alcanzado.")
                : core.prestigeCount() < requiredFracture
                        ? item.getString("locked-status", "&cRequiere Fractura %required_fracture%.")
                        : core.coreFragments() < cost
                                ? item.getString("fragments-status", "&cNecesitas %cost% fragmentos.")
                                : item.getString("available-status", "&eHaz clic para mejorar.");
        status = replace(status, Map.of(
                "%required_fracture%", Integer.toString(requiredFracture),
                "%cost%", Integer.toString(cost)));
        Map<String, String> placeholders = Map.of(
                "%level%", Integer.toString(level),
                "%max_level%", Integer.toString(skill.maxLevel()),
                "%required_fracture%", Integer.toString(requiredFracture),
                "%cost%", Integer.toString(cost),
                "%fragments%", Integer.toString(core.coreFragments()),
                "%bonus%", Integer.toString((int) Math.round(skill.bonusPerLevel() * 100)),
                "%status%", status);
        return simpleItem(path, placeholders);
    }

    private ItemStack simpleItem(String path, Map<String, String> placeholders) {
        ConfigurationSection item = config.getConfigurationSection(path);
        ItemStack stack = new ItemStack(Material.matchMaterial(item.getString("material", "PAPER")));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(color(replace(item.getString("display-name", ""), placeholders)));
        List<Component> lore = item.getStringList("lore").stream()
                .map(line -> color(replace(line, placeholders)))
                .toList();
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void validateItem(YamlConfiguration loaded, String path, int expectedSlot) {
        ConfigurationSection item = loaded.getConfigurationSection(path);
        List<Integer> configuredSlots = item == null
                ? List.of()
                : item.contains("slots") ? item.getIntegerList("slots") : List.of(item.getInt("slot", -1));
        if (item == null || configuredSlots.isEmpty()
                || configuredSlots.stream().anyMatch(slot -> slot < 1 || slot > 54)
                || (expectedSlot >= 0 && configuredSlots.get(0) - 1 != expectedSlot)
                || Material.matchMaterial(item.getString("material", "")) == null) {
            throw new IllegalArgumentException("astral_tree.yml " + path + " must define a valid item and slot.");
        }
    }

    private int slot(String item) {
        return config.getInt("items." + item + ".slot") - 1;
    }

    private String replace(String text, Map<String, String> placeholders) {
        String result = text;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return result;
    }

    private Component color(String text) {
        return messages.parseComponent(text);
    }
}
