package com.corebound.thecore.gui;

import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class IslandMenuManager implements Listener {
    private enum MenuType {
        SETTINGS,
        MEMBERS,
        DELETE
    }

    private static final DateTimeFormatter JOIN_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin;
    private final IslandRepository repository;
    private final IslandService islandService;
    private final Messages messages;
    private final YamlConfiguration settingsConfig;
    private final YamlConfiguration membersConfig;

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final UUID islandOwner;
        private final int page;
        private Inventory inventory;

        private MenuHolder(MenuType type, UUID islandOwner, int page) {
            this.type = type;
            this.islandOwner = islandOwner;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public IslandMenuManager(
            JavaPlugin plugin, IslandRepository repository, IslandService islandService, Messages messages) {
        this.plugin = plugin;
        this.repository = repository;
        this.islandService = islandService;
        this.messages = messages;
        settingsConfig = YamlConfiguration.loadConfiguration(
                plugin.getDataFolder().toPath().resolve("is/is_settings.yml").toFile());
        membersConfig = YamlConfiguration.loadConfiguration(
                plugin.getDataFolder().toPath().resolve("is/is_members.yml").toFile());
    }

    public void openSettings(Player player, IslandData island) {
        int rows = menuRows(settingsConfig, "is_settings.yml");
        MenuHolder holder = new MenuHolder(MenuType.SETTINGS, island.owner(), 0);
        holder.inventory = Bukkit.createInventory(
                holder, rows * 9, color(settingsConfig.getString("title", "&8Island Settings")));
        ConfigurationSection items = settingsConfig.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection itemConfig = items.getConfigurationSection(key);
                if (itemConfig == null || !island.settings().values().containsKey(key)) {
                    continue;
                }
                boolean enabled = island.settings().values().get(key);
                String state = settingsConfig.getString(enabled ? "enabled-text" : "disabled-text",
                        enabled ? "&aEnabled" : "&cDisabled");
                List<String> lore = itemConfig.getStringList(enabled ? "lore-enabled" : "lore-disabled");
                ItemStack item = item(itemConfig.getString("material", "PAPER"),
                        itemConfig.getInt("slot", -1),
                        itemConfig.getString("display-name", key),
                        lore.stream().map(line -> line.replace("%status%", state)).toList());
                if (item != null && itemConfig.getInt("slot", -1) >= 0
                        && itemConfig.getInt("slot", -1) < holder.inventory.getSize()) {
                    holder.inventory.setItem(itemConfig.getInt("slot"), item);
                }
            }
        }
        player.openInventory(holder.inventory);
    }

    public void openMembers(Player player, IslandData island, int page) {
        int rows = menuRows(membersConfig, "is_members.yml");
        List<Integer> slots = membersConfig.getIntegerList("member-slots");
        List<MemberEntry> entries = new ArrayList<>();
        entries.add(new MemberEntry(island.owner(), island.createdAt(), true));
        island.members().forEach((id, joinedAt) -> entries.add(new MemberEntry(id, joinedAt, false)));
        entries.sort(Comparator.comparing(MemberEntry::owner).reversed()
                .thenComparingLong(MemberEntry::joinedAt));
        int pageSize = slots.size();
        int lastPage = Math.max(0, (entries.size() - 1) / Math.max(pageSize, 1));
        MenuHolder holder = new MenuHolder(MenuType.MEMBERS, island.owner(), Math.min(Math.max(page, 0), lastPage));
        holder.inventory = Bukkit.createInventory(
                holder, rows * 9, color(membersConfig.getString("title", "&8Island Members")));
        int start = holder.page * pageSize;
        for (int i = 0; i < pageSize && start + i < entries.size(); i++) {
            int slot = slots.get(i);
            if (slot < 0 || slot >= holder.inventory.getSize()) {
                continue;
            }
            MemberEntry member = entries.get(start + i);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(member.playerId());
            String name = offlinePlayer.getName() == null ? member.playerId().toString() : offlinePlayer.getName();
            String rank = member.owner()
                    ? membersConfig.getString("owner-rank", "Owner")
                    : membersConfig.getString("member-rank", "Member");
            String joinedAt = member.joinedAt() <= 0
                    ? membersConfig.getString("unknown-time", "Unknown")
                    : JOIN_DATE.format(Instant.ofEpochMilli(member.joinedAt()));
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(offlinePlayer);
            meta.displayName(text(membersConfig.getString("display-name", "&f%player%")
                    .replace("%player%", name)));
            meta.lore(membersConfig.getStringList("lore").stream()
                    .map(line -> text(line.replace("%rank%", rank).replace("%time%", joinedAt)))
                    .toList());
            head.setItemMeta(meta);
            holder.inventory.setItem(slot, head);
        }
        if (holder.page > 0) {
            setConfiguredItem(holder.inventory, membersConfig, "previous-page", "slot-previous", 27);
        }
        if (holder.page < lastPage) {
            setConfiguredItem(holder.inventory, membersConfig, "next-page", "slot-next", 35);
        }
        player.openInventory(holder.inventory);
    }

    public void openDeleteConfirmation(Player player, IslandData island) {
        MenuHolder holder = new MenuHolder(MenuType.DELETE, island.owner(), 0);
        holder.inventory = Bukkit.createInventory(holder, 27, messages.colored("delete-title"));
        holder.inventory.setItem(11, named(Material.LIME_CONCRETE, messages.colored("delete-yes")));
        holder.inventory.setItem(15, named(Material.RED_CONCRETE, messages.colored("delete-no")));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        IslandData island = repository.byOwner(holder.islandOwner).orElse(null);
        if (holder.type == MenuType.DELETE) {
            if (event.getRawSlot() == 15) {
                player.closeInventory();
            } else if (event.getRawSlot() == 11 && island != null && island.owner().equals(player.getUniqueId())) {
                player.closeInventory();
                if (islandService.isDeleting(island.owner())) {
                    messages.send(player, "island-being-deleted");
                    return;
                }
                islandService.deleteIsland(island, success -> {
                    if (success) {
                        messages.send(player, "island-deleted");
                    } else {
                        messages.send(player, "island-delete-failed");
                    }
                });
            }
            return;
        }
        if (island == null) {
            player.closeInventory();
            return;
        }
        if (holder.type == MenuType.SETTINGS) {
            if (!island.owner().equals(player.getUniqueId())) {
                player.closeInventory();
                return;
            }
            ConfigurationSection items = settingsConfig.getConfigurationSection("items");
            if (items == null) {
                return;
            }
            for (String key : items.getKeys(false)) {
                ConfigurationSection itemConfig = items.getConfigurationSection(key);
                if (itemConfig == null || itemConfig.getInt("slot", -1) != event.getRawSlot()) {
                    continue;
                }
                try {
                    repository.replace(island.withSettings(island.settings().toggle(key)));
                    openSettings(player, repository.byOwner(island.owner()).orElseThrow());
                } catch (java.io.IOException exception) {
                    plugin.getLogger().severe("Could not save island settings: " + exception.getMessage());
                    messages.send(player, "data-save-failed");
                }
                return;
            }
        } else if (holder.type == MenuType.MEMBERS) {
            int slotPrevious = membersConfig.getInt("slot-previous", 27);
            int slotNext = membersConfig.getInt("slot-next", 35);
            if (event.getRawSlot() == slotPrevious && holder.page > 0) {
                openMembers(player, island, holder.page - 1);
            } else if (event.getRawSlot() == slotNext) {
                openMembers(player, island, holder.page + 1);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private record MemberEntry(UUID playerId, long joinedAt, boolean owner) {
    }

    private void setConfiguredItem(
            Inventory inventory, YamlConfiguration config, String path, String slotPath, int fallbackSlot) {
        ConfigurationSection section = config.getConfigurationSection(path);
        int slot = config.getInt(slotPath, fallbackSlot);
        if (section == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        ItemStack item = item(section.getString("material", "ARROW"), slot,
                section.getString("display-name", path), section.getStringList("lore"));
        if (item != null) {
            inventory.setItem(slot, item);
        }
    }

    private ItemStack item(String materialName, int slot, String name, List<String> lore) {
        Material material = Material.matchMaterial(materialName);
        if (material == null || slot < 0) {
            return null;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name));
        meta.lore(lore.stream().map(this::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name));
        item.setItemMeta(meta);
        return item;
    }

    private net.kyori.adventure.text.Component text(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value);
    }

    private String color(String value) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', value);
    }

    private int menuRows(YamlConfiguration config, String filename) {
        int rows = config.getInt("rows", 4);
        if (rows < 1 || rows > 6) {
            plugin.getLogger().warning(filename + " has an invalid row count; using 4 rows.");
            return 4;
        }
        return rows;
    }
}
