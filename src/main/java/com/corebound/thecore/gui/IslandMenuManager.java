package com.corebound.thecore.gui;

import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandPermission;
import com.corebound.thecore.island.IslandRank;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.island.IslandSounds;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import com.corebound.thecore.config.ConfigFiles;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextDecoration;
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
        PERMISSIONS
    }

    private static final DateTimeFormatter JOIN_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter JOIN_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin;
    private final IslandRepository repository;
    private final IslandService islandService;
    private final IslandSounds sounds;
    private final Messages messages;
    private YamlConfiguration settingsConfig;
    private YamlConfiguration membersConfig;
    private YamlConfiguration permissionsConfig;

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final UUID islandOwner;
        private final int page;
        private final UUID target;
        private Inventory inventory;

        private MenuHolder(MenuType type, UUID islandOwner, int page) {
            this(type, islandOwner, page, null);
        }

        private MenuHolder(MenuType type, UUID islandOwner, int page, UUID target) {
            this.type = type;
            this.islandOwner = islandOwner;
            this.page = page;
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public IslandMenuManager(
            JavaPlugin plugin,
            IslandRepository repository,
            IslandService islandService,
            Messages messages,
            IslandSounds sounds) {
        this.plugin = plugin;
        this.repository = repository;
        this.islandService = islandService;
        this.messages = messages;
        this.sounds = sounds;
        reload();
    }

    public void reload() {
        settingsConfig = ConfigFiles.load(
                plugin.getDataFolder().toPath().resolve("is/is_settings.yml").toFile(),
                plugin.getResource("is/is_settings.yml"));
        membersConfig = ConfigFiles.load(
                plugin.getDataFolder().toPath().resolve("is/is_members.yml").toFile(),
                plugin.getResource("is/is_members.yml"));
        permissionsConfig = ConfigFiles.load(
                plugin.getDataFolder().toPath().resolve("is/is_perms.yml").toFile(),
                plugin.getResource("is/is_perms.yml"));
    }

    public void openSettings(Player player, IslandData island) {
        int rows = menuRows(settingsConfig, "is_settings.yml");
        MenuHolder holder = new MenuHolder(MenuType.SETTINGS, island.owner(), 0);
        holder.inventory = Bukkit.createInventory(
                holder, rows * 9, LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(settingsConfig.getString("title")));
        ConfigurationSection items = settingsConfig.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection itemConfig = items.getConfigurationSection(key);
                if (itemConfig == null || !island.settings().values().containsKey(key)) {
                    continue;
                }
                boolean enabled = island.settings().values().get(key);
                String state = settingsConfig.getString(enabled ? "enabled-text" : "disabled-text");
                List<String> lore = itemConfig.getStringList(enabled ? "lore-enabled" : "lore-disabled");
                ItemStack item = item(itemConfig.getString("material", "PAPER"),
                        itemConfig.getInt("slot", -1),
                        itemConfig.getString("display-name"),
                        lore.stream().map(line -> line.replace("%status%", state)).toList());
                if (item != null && itemConfig.getInt("slot", -1) >= 0
                        && itemConfig.getInt("slot", -1) < holder.inventory.getSize()) {
                    holder.inventory.setItem(itemConfig.getInt("slot"), item);
                }
            }
        }
        player.openInventory(holder.inventory);
    }

    public void openPermissions(Player player, IslandData island, UUID target) {
        if (!island.hasPermission(player.getUniqueId(), IslandPermission.MANAGE_PERMS)) {
            messages.send(player, "island-permission-denied");
            return;
        }
        if (target != null && !island.members().containsKey(target)) {
            messages.send(player, "island-member-not-found");
            return;
        }
        MenuHolder holder = new MenuHolder(MenuType.PERMISSIONS, island.owner(), 0, target);
        String title = target == null
                ? permissionsConfig.getString("title")
                : permissionsConfig.getString("user-title")
                        .replace("%user%", playerName(target));
        int rows = menuRows(permissionsConfig, "is_perms.yml");
        holder.inventory = Bukkit.createInventory(
                holder, rows * 9, LegacyComponentSerializer.legacyAmpersand().deserialize(title));
        if (target == null) {
            ConfigurationSection items = permissionsConfig.getConfigurationSection("items");
            if (items == null) {
                return;
            }
            for (IslandPermission permission : IslandPermission.values()) {
                ConfigurationSection itemConfig = items.getConfigurationSection(permission.configKey());
                if (itemConfig == null) {
                    continue;
                }
                IslandRank minimum = island.permissionMinimums().getOrDefault(permission, IslandRank.MEMBER);
                List<String> lore = new ArrayList<>();
                for (String line : itemConfig.getStringList("lore")) {
                    if (line.contains("%available-ranks%")) {
                        for (IslandRank rank : List.of(
                                IslandRank.ADMINISTRATOR, IslandRank.MODERATOR, IslandRank.MEMBER)) {
                            String style = permissionsConfig.getString(
                                    rank == minimum ? "rank-enabled-text" : "rank-disabled-text");
                            lore.add(style.replace("%rank%", messages.text(rank.messageKey())));
                        }
                    } else {
                        lore.add(line);
                    }
                }
                setConfiguredItem(holder.inventory, itemConfig, itemConfig.getInt("slot", -1),
                        itemConfig.getString("display-name"), lore);
            }
            sounds.play(player, permissionsConfig, "sounds.open");
        } else {
            ConfigurationSection items = permissionUserItems();
            if (items == null) {
                return;
            }
            for (IslandPermission permission : IslandPermission.values()) {
                ConfigurationSection itemConfig = items.getConfigurationSection(permission.configKey());
                if (itemConfig == null) {
                    continue;
                }
                boolean enabled = island.hasPermission(target, permission);
                List<String> lore = itemConfig.getStringList(enabled ? "lore-enabled" : "lore-disabled")
                        .stream()
                        .map(line -> line.replace("%status%",
                                permissionsConfig.getString(enabled ? "enabled-text" : "disabled-text")))
                        .toList();
                setConfiguredItem(holder.inventory, itemConfig,
                        permissionUserSlot(permission.configKey()),
                        itemConfig.getString("display-name"), lore);
            }
            ConfigurationSection allItem = items.getConfigurationSection("all-permissions");
            boolean allEnabled = island.hasAllPermissions(target);
            if (allItem != null) {
                List<String> lore = allItem.getStringList(allEnabled ? "lore-enabled" : "lore-disabled")
                        .stream()
                        .map(line -> line.replace("%status%",
                                permissionsConfig.getString(allEnabled ? "enabled-text" : "disabled-text")))
                        .toList();
                setConfiguredItem(holder.inventory, allItem,
                        permissionUserSlot("all-permissions"),
                        allItem.getString("display-name"), lore);
            }
            sounds.play(player, permissionsConfig, "sounds.user-open");
        }
        player.openInventory(holder.inventory);
    }

    private ConfigurationSection permissionUserItems() {
        ConfigurationSection items = permissionsConfig.getConfigurationSection("user-items");
        return items == null ? permissionsConfig.getConfigurationSection("items.user-items") : items;
    }

    private int permissionUserSlot(String key) {
        ConfigurationSection items = permissionUserItems();
        ConfigurationSection item = items == null ? null : items.getConfigurationSection(key);
        if (item != null && item.contains("slot")) {
            return item.getInt("slot");
        }
        String path = "user-slots." + key;
        if (permissionsConfig.contains(path)) {
            return permissionsConfig.getInt(path);
        }
        return permissionsConfig.getInt("items." + path, -1);
    }

    private String playerName(UUID playerId) {
        return Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName())
                .orElse(playerId.toString());
    }

    public void openMembers(Player player, IslandData island, int page) {
        int rows = menuRows(membersConfig, "is_members.yml");
        List<Integer> slots = membersConfig.getIntegerList("member-slots");
        List<MemberEntry> entries = new ArrayList<>();
        entries.add(new MemberEntry(island.owner(), island.createdAt(), IslandRank.OWNER));
        island.members().forEach((id, joinedAt) ->
                entries.add(new MemberEntry(id, joinedAt, island.rank(id))));
        entries.sort(Comparator.comparingInt((MemberEntry entry) -> entry.rank().level()).reversed()
                .thenComparingLong(entry -> entry.joinedAt()));
        int pageSize = slots.size();
        int lastPage = Math.max(0, (entries.size() - 1) / Math.max(pageSize, 1));
        MenuHolder holder = new MenuHolder(MenuType.MEMBERS, island.owner(), Math.min(Math.max(page, 0), lastPage));
        holder.inventory = Bukkit.createInventory(
                holder, rows * 9, LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(membersConfig.getString("title")));
        int start = holder.page * pageSize;
        for (int i = 0; i < pageSize && start + i < entries.size(); i++) {
            int slot = slots.get(i);
            if (slot < 0 || slot >= holder.inventory.getSize()) {
                continue;
            }
            MemberEntry member = entries.get(start + i);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(member.playerId());
            String name = offlinePlayer.getName() == null ? member.playerId().toString() : offlinePlayer.getName();
            String rank = messages.text(member.rank().messageKey());
            String joinedDate = member.joinedAt() <= 0
                    ? membersConfig.getString("unknown-time")
                    : JOIN_DATE.format(Instant.ofEpochMilli(member.joinedAt()));
            String joinedTime = member.joinedAt() <= 0
                    ? membersConfig.getString("unknown-time")
                    : JOIN_TIME.format(Instant.ofEpochMilli(member.joinedAt()));
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            Player onlinePlayer = Bukkit.getPlayer(member.playerId());
            if (onlinePlayer != null) {
                meta.setPlayerProfile(onlinePlayer.getPlayerProfile());
            }
            meta.displayName(text(membersConfig.getString("display-name")
                    .replace("%player%", name)));
            meta.lore(membersConfig.getStringList("lore").stream()
                    .map(line -> text(line.replace("%rank%", rank)
                            .replace("%date%", joinedDate)
                            .replace("%time%", joinedTime)))
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
        if (!island.hasPermission(player.getUniqueId(), IslandPermission.DELETE)) {
            messages.send(player, "island-permission-denied");
            return;
        }
        ActionButton yesButton = ActionButton.builder(messageComponent("delete-yes"))
                .action(DialogAction.customClick(
                        (response, audience) -> {
                            if (audience instanceof Player clicker) {
                                confirmDelete(clicker, island.owner());
                            }
                        },
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(Duration.ofMinutes(5))
                                .build()))
                .build();
        ActionButton noButton = ActionButton.builder(messageComponent("delete-no")).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(messageComponent("delete-title"))
                        .body(List.of(DialogBody.plainMessage(messageComponent("delete-dialog-body"))))
                        .build())
                .type(DialogType.confirmation(yesButton, noButton)));
        player.showDialog(dialog);
    }

    private void confirmDelete(Player player, UUID islandOwner) {
        IslandData island = repository.byOwner(islandOwner).orElse(null);
        if (island == null) {
            messages.send(player, "island-not-created");
            return;
        }
        if (!island.hasPermission(player.getUniqueId(), IslandPermission.DELETE)) {
            messages.send(player, "island-permission-denied");
            return;
        }
        if (islandService.isDeleting(islandOwner)) {
            messages.send(player, "island-being-deleted");
            return;
        }
        islandService.deleteIsland(island, success -> player.getScheduler().run(
                plugin, task -> messages.send(player, success ? "island-deleted" : "island-delete-failed"), null));
    }

    public void openTransferConfirmation(Player player, IslandData island, UUID target) {
        if (!island.owner().equals(player.getUniqueId()) || !island.members().containsKey(target)) {
            messages.send(player, "transfer-invalid-target");
            return;
        }
        ActionButton yesButton = ActionButton.builder(messageComponent("transfer-confirm-yes"))
                .action(DialogAction.customClick(
                        (response, audience) -> {
                            if (audience instanceof Player clicker
                                    && clicker.getUniqueId().equals(island.owner())) {
                                islandService.transferOwnership(clicker, island, target);
                            }
                        },
                        ClickCallback.Options.builder()
                                .uses(1)
                                .lifetime(Duration.ofMinutes(5))
                                .build()))
                .build();
        ActionButton noButton = ActionButton.builder(messageComponent("transfer-confirm-no")).build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(messages.format("transfer-confirm-title", Map.of(
                                "user", Component.text(playerName(target)))))
                        .body(List.of(DialogBody.plainMessage(messages.format("transfer-confirm-body", Map.of(
                                "user", Component.text(playerName(target)))))))
                        .build())
                .type(DialogType.confirmation(yesButton, noButton)));
        player.showDialog(dialog);
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
        if (island == null) {
            player.closeInventory();
            return;
        }
        if (holder.type == MenuType.SETTINGS) {
            if (!island.hasPermission(player.getUniqueId(), IslandPermission.SETTINGS)) {
                player.closeInventory();
                messages.send(player, "island-permission-denied");
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
                    IslandData updated = island.withSettings(island.settings().toggle(key));
                    repository.replace(updated);
                    islandService.refreshIslandEnvironment(updated);
                    sounds.play(player, "sounds.settings-toggle");
                    openSettings(player, updated);
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
        } else if (holder.type == MenuType.PERMISSIONS) {
            if (!island.hasPermission(player.getUniqueId(), IslandPermission.MANAGE_PERMS)) {
                player.closeInventory();
                messages.send(player, "island-permission-denied");
                return;
            }
            if (holder.target == null) {
                handlePermissionRankClick(player, island, event.getRawSlot(), event.getClick());
            } else {
                handleMemberPermissionClick(player, island, holder.target, event.getRawSlot());
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handlePermissionRankClick(
            Player player, IslandData island, int slot, org.bukkit.event.inventory.ClickType click) {
        ConfigurationSection items = permissionsConfig.getConfigurationSection("items");
        if (items == null) {
            return;
        }
        for (IslandPermission permission : IslandPermission.values()) {
            ConfigurationSection item = items.getConfigurationSection(permission.configKey());
            if (item == null || item.getInt("slot", -1) != slot) {
                continue;
            }
            IslandRank current = island.permissionMinimums().getOrDefault(permission, IslandRank.MEMBER);
            int direction = click.isLeftClick() ? 1 : click.isRightClick() ? -1 : 0;
            int nextLevel = Math.max(IslandRank.MEMBER.level(),
                    Math.min(IslandRank.ADMINISTRATOR.level(), current.level() + direction));
            IslandRank next = IslandRank.values()[nextLevel];
            if (next != current && islandService.setPermissionMinimum(player, island, permission, next)) {
                sounds.play(player, permissionsConfig, "sounds.change");
                openPermissions(player, repository.byOwner(island.owner()).orElseThrow(), null);
            }
            return;
        }
    }

    private void handleMemberPermissionClick(Player player, IslandData island, UUID target, int slot) {
        if (!island.members().containsKey(target)) {
            player.closeInventory();
            messages.send(player, "island-member-not-found");
            return;
        }
        if (island.rank(target) == IslandRank.ADMINISTRATOR) {
            player.closeInventory();
            messages.send(player, "administrator-has-all-permissions");
            return;
        }
        ConfigurationSection items = permissionUserItems();
        if (items == null) {
            return;
        }
        if (permissionUserSlot("all-permissions") == slot) {
            if (islandService.setAllPermissionsOverride(
                    player, island, target, !island.hasAllPermissions(target))) {
                sounds.play(player, permissionsConfig, "sounds.change");
                openPermissions(player, repository.byOwner(island.owner()).orElseThrow(), target);
            }
            return;
        }
        for (IslandPermission permission : IslandPermission.values()) {
            ConfigurationSection item = items.getConfigurationSection(permission.configKey());
            if (item == null || permissionUserSlot(permission.configKey()) != slot) {
                continue;
            }
            boolean enabled = !island.hasPermission(target, permission);
            if (islandService.setPermissionOverride(player, island, target, permission, enabled)) {
                sounds.play(player, permissionsConfig, "sounds.change");
                openPermissions(player, repository.byOwner(island.owner()).orElseThrow(), target);
            }
            return;
        }
    }

    private record MemberEntry(UUID playerId, long joinedAt, IslandRank rank) {
    }

    private void setConfiguredItem(
            Inventory inventory, YamlConfiguration config, String path, String slotPath, int fallbackSlot) {
        ConfigurationSection section = config.getConfigurationSection(path);
        setConfiguredItem(inventory, section, config.getInt(slotPath, fallbackSlot),
                section == null ? "" : section.getString("display-name"),
                section == null ? List.of() : section.getStringList("lore"));
    }

    private void setConfiguredItem(
            Inventory inventory, ConfigurationSection section, int slot, String name, List<String> lore) {
        if (section == null || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        ItemStack item = item(section.getString("material", "ARROW"), slot, name, lore);
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

    private Component text(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component messageComponent(String key) {
        return LegacyComponentSerializer.legacySection().deserialize(messages.colored(key))
                .decoration(TextDecoration.ITALIC, false);
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
