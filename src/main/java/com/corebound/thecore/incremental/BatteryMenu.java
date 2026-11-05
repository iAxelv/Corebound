package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class BatteryMenu implements Listener {
    private static final int INFO_SLOT = 4;
    private static final int ENERGY_SLOT = 20;
    private static final int RUNES_SLOT = 22;
    private static final int UPGRADE_SLOT = 24;
    private static final int CLOSE_SLOT = 40;
    private static final int RUNE_INFO_SLOT = 4;
    private static final int RUNE_PROGRESSION_SLOT = 22;
    private static final int RUNE_BACK_SLOT = 26;
    private static final int[] RUNE_LAYOUT_ONE = {13};
    private static final int[] RUNE_LAYOUT_TWO = {12, 14};
    private static final int[] RUNE_LAYOUT_THREE = {11, 13, 15};

    private static final class Holder implements InventoryHolder {
        private final UUID batteryId;
        private final boolean runeMenu;
        private Inventory inventory;

        private Holder(UUID batteryId, boolean runeMenu) {
            this.batteryId = batteryId;
            this.runeMenu = runeMenu;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Messages messages;
    private final BatteryService batteries;

    public BatteryMenu(Messages messages, BatteryService batteries) {
        this.messages = messages;
        this.batteries = batteries;
    }

    public void open(Player player, BatteryRecord battery) {
        Holder holder = new Holder(battery.batteryId(), false);
        holder.inventory = Bukkit.createInventory(holder, 45, messages.component("battery-ui.title"));
        fillFrame(holder.inventory);
        holder.inventory.setItem(INFO_SLOT, infoItem(battery));
        holder.inventory.setItem(ENERGY_SLOT, energyItem(battery));
        holder.inventory.setItem(RUNES_SLOT, runesItem(battery));
        holder.inventory.setItem(UPGRADE_SLOT, upgradeItem(battery));
        holder.inventory.setItem(CLOSE_SLOT, closeItem("battery-ui.close"));
        player.openInventory(holder.inventory);
    }

    private void openRunes(Player player, BatteryRecord battery) {
        Holder holder = new Holder(battery.batteryId(), true);
        holder.inventory = Bukkit.createInventory(holder, 27, messages.component("battery-ui.runes-title"));
        fillRuneMenu(holder.inventory);
        int[] slots = runeSlots(battery.tier());
        for (int index = 0; index < slots.length; index++) {
            CellRecord rune = battery.runes().get(index);
            holder.inventory.setItem(
                    slots[index], rune == null ? emptyRuneSlot() : CellItem.create(
                            rune, batteries.baseIntervalTicks(), messages));
        }
        holder.inventory.setItem(RUNE_INFO_SLOT, runeInfoItem(battery));
        holder.inventory.setItem(RUNE_PROGRESSION_SLOT, progressionItem(battery));
        holder.inventory.setItem(RUNE_BACK_SLOT, closeItem("battery-ui.back"));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        BatteryRecord battery = batteries.record(holder.batteryId);
        if (battery == null || !batteries.canManage(player, battery)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (holder.runeMenu) {
            onRuneMenuClick(event, player, holder, battery);
        } else {
            onMainMenuClick(event, player, holder, battery);
        }
    }

    private void onMainMenuClick(
            InventoryClickEvent event, Player player, Holder holder, BatteryRecord battery) {
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        switch (event.getRawSlot()) {
            case CLOSE_SLOT -> player.closeInventory();
            case RUNES_SLOT -> openRunes(player, battery);
            case UPGRADE_SLOT -> {
                batteries.upgrade(player, holder.batteryId);
                reopen(player, holder.batteryId, false);
            }
            default -> {
            }
        }
    }

    private void onRuneMenuClick(
            InventoryClickEvent event, Player player, Holder holder, BatteryRecord battery) {
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                CellRecord rune = CellItem.read(event.getCurrentItem());
                if (rune == null) {
                    return;
                }
                if (!rune.type().isBatteryRune()) {
                    messages.send(player, "battery-rune-wrong-type");
                    return;
                }
                if (batteries.installFirstAvailableRune(player, holder.batteryId, event.getSlot(), rune)) {
                    player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
                    reopen(player, holder.batteryId, true);
                }
            } else if (event.getClickedInventory() != null) {
                fuseInventoryRunes(player, event);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == RUNE_BACK_SLOT) {
            open(player, battery);
            return;
        }
        if (event.getRawSlot() == RUNE_PROGRESSION_SLOT && battery.tier() < 3) {
            int nextTier = battery.tier() + 1;
            CoreRecord core = batteries.core(battery.islandIndex());
            if (core != null && core.level() >= batteries.upgradeRequiredLevel(nextTier)
                    && batteries.upgrade(player, holder.batteryId)) {
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
                reopen(player, holder.batteryId, true);
            }
            return;
        }
        int runeIndex = runeIndex(event.getRawSlot(), battery.tier());
        if (runeIndex >= 0 && battery.runes().containsKey(runeIndex)) {
            if (batteries.removeRune(player, holder.batteryId, runeIndex)) {
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
                reopen(player, holder.batteryId, true);
            }
        }
    }

    private void fuseInventoryRunes(Player player, InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();
        CellRecord first = CellItem.read(cursor);
        CellRecord second = CellItem.read(target);
        if (first == null || second == null || !first.type().isBatteryRune()
                || first.type() != second.type() || first.tier() != second.tier()
                || cursor.getAmount() != 1 || target.getAmount() != 1
                || first.tier() >= CellRecord.MAX_TIER) {
            return;
        }
        CellRecord fused = first.fuse(second);
        if (fused == null || !batteries.canFuseRuneTier(player, fused.tier())) {
            return;
        }
        event.setCancelled(true);
        player.setItemOnCursor(CellItem.create(fused, batteries.baseIntervalTicks(), messages));
        event.getClickedInventory().setItem(event.getSlot(), null);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
        messages.send(player, "battery-rune-fused", Map.of(
                "rune", fused.type().displayName(messages),
                "tier", Integer.toString(fused.tier())));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void reopen(Player player, UUID batteryId, boolean runeMenu) {
        BatteryRecord battery = batteries.record(batteryId);
        if (battery == null) {
            player.closeInventory();
        } else if (runeMenu) {
            openRunes(player, battery);
        } else {
            open(player, battery);
        }
    }

    private ItemStack infoItem(BatteryRecord battery) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(messages.formatText("battery-ui.info-name", Map.of(
                "level", Integer.toString(battery.tier()),
                "max_level", "3"))));
        CoreRecord core = batteries.core(battery.islandIndex());
        double capacity = core == null ? 0 : batteries.capacity(battery, core);
        String offlineLore = messages.formatText("battery-ui.offline", Map.of(
                "hours", formatHours(batteries.maximumOfflineHours(battery.islandIndex()))));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("battery-ui.capacity", Map.of(
                        "energy", format(battery.energy()),
                        "capacity", format(capacity)))),
                messages.parseComponent(offlineLore)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack energyItem(BatteryRecord battery) {
        ItemStack item = new ItemStack(Material.COPPER_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.energy-name"));
        CoreRecord core = batteries.core(battery.islandIndex());
        double capacity = core == null ? 0 : batteries.capacity(battery, core);
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("battery-ui.capacity", Map.of(
                        "energy", format(battery.energy()),
                        "capacity", format(capacity)))),
                Component.empty(),
                messages.component("battery-ui.energy-description")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack runesItem(BatteryRecord battery) {
        ItemStack item = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.runes-name"));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("battery-ui.runes-slots", Map.of(
                        "active", Integer.toString(battery.runes().size()),
                        "slots", Integer.toString(battery.runeSlots())))),
                messages.component("battery-ui.runes-description"),
                Component.empty(),
                messages.component("battery-ui.manage-runes")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack runeInfoItem(BatteryRecord battery) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.runes-statistics"));
        meta.lore(List.of(messages.parseComponent(messages.formatText("battery-ui.runes-slots", Map.of(
                "active", Integer.toString(battery.runes().size()),
                "slots", Integer.toString(battery.runeSlots())))),
                messages.parseComponent(messages.formatText("battery-ui.runes-level", Map.of(
                        "level", Integer.toString(battery.tier()))))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack progressionItem(BatteryRecord battery) {
        boolean maxed = battery.tier() >= 3;
        int nextLevel = battery.tier() + 1;
        int requiredLevel = maxed ? 0 : batteries.upgradeRequiredLevel(nextLevel);
        CoreRecord core = batteries.core(battery.islandIndex());
        boolean unlocked = !maxed && core != null && core.level() >= requiredLevel;
        ItemStack item = new ItemStack(maxed ? Material.NETHER_STAR
                : unlocked ? Material.ANVIL : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component(maxed
                ? "battery-ui.runes-max-name"
                : unlocked ? "battery-ui.runes-unlocked-name" : "battery-ui.runes-locked-name"));
        if (maxed) {
            meta.lore(List.of(messages.component("battery-ui.runes-max-lore")));
        } else if (unlocked) {
            meta.lore(List.of(
                    messages.parseComponent(messages.formatText("battery-ui.runes-unlocked-slots", Map.of(
                            "slots_current", Integer.toString(battery.runeSlots()),
                            "slots_next", Integer.toString(Math.min(3, battery.runeSlots() + 1))))),
                    messages.parseComponent(messages.formatText("battery-ui.runes-unlocked-cost", Map.of(
                            "cost", format(batteries.upgradeCost(nextLevel))))),
                    Component.empty(),
                    messages.component("battery-ui.runes-unlocked-click")));
        } else {
            meta.lore(List.of(
                    messages.parseComponent(messages.formatText("battery-ui.runes-locked-level", Map.of(
                            "required_level", Integer.toString(requiredLevel)))),
                    messages.parseComponent(messages.formatText("battery-ui.runes-locked-slots", Map.of(
                            "slots_current", Integer.toString(battery.runeSlots()),
                            "slots_next", Integer.toString(Math.min(3, battery.runeSlots() + 1)))))));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyRuneSlot() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.empty-rune"));
        meta.lore(List.of(
                messages.component("battery-ui.install-rune"),
                messages.component("battery-ui.install-rune-next-line"),
                Component.empty(),
                messages.component("battery-ui.remove-rune"),
                messages.component("battery-ui.remove-rune-next-line")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(BatteryRecord battery) {
        boolean maxed = battery.tier() >= 3;
        ItemStack item = new ItemStack(maxed ? Material.NETHER_STAR : Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(messages.formatText(
                maxed ? "battery-ui.max-level" : "battery-ui.upgrade-name",
                Map.of("level", Integer.toString(battery.tier() + 1)))));
        if (maxed) {
            meta.lore(List.of(messages.component("battery-ui.max-level-lore")));
        } else {
            int nextLevel = battery.tier() + 1;
            meta.lore(List.of(
                    messages.parseComponent(messages.formatText("battery-ui.upgrade-cost", Map.of(
                            "cost", format(batteries.upgradeCost(nextLevel))))),
                    messages.parseComponent(messages.formatText("battery-ui.upgrade-required-level", Map.of(
                            "level", Integer.toString(batteries.upgradeRequiredLevel(nextLevel))))),
                    Component.empty(),
                    messages.component("battery-ui.click-upgrade")));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem(String messageKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component(messageKey));
        item.setItemMeta(meta);
        return item;
    }

    private int[] runeSlots(int unlockedSlots) {
        return switch (unlockedSlots) {
            case 1 -> RUNE_LAYOUT_ONE;
            case 2 -> RUNE_LAYOUT_TWO;
            default -> RUNE_LAYOUT_THREE;
        };
    }

    private int runeIndex(int rawSlot, int unlockedSlots) {
        int[] slots = runeSlots(unlockedSlots);
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    private void fillFrame(Inventory inventory) {
        fill(inventory, Material.GRAY_STAINED_GLASS_PANE, true);
    }

    private void fillRuneMenu(Inventory inventory) {
        fill(inventory, Material.PURPLE_STAINED_GLASS_PANE, false);
    }

    private void fill(Inventory inventory, Material material, boolean frameOnly) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!frameOnly || slot < 9 || slot >= 36 || slot % 9 == 0 || slot % 9 == 8) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private String format(double energy) {
        return String.format(java.util.Locale.ROOT, "%.1f", energy);
    }

    private String formatHours(double millis) {
        return String.format(java.util.Locale.ROOT, "%.1f", millis / 3_600_000.0);
    }
}
