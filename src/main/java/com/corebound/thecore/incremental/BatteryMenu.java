package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
    private static final int INFO_SLOT = 13;
    private static final int[] RUNE_SLOTS = {20, 22, 24};
    private static final int UPGRADE_SLOT = 31;
    private static final int CLOSE_SLOT = 40;

    private static final class Holder implements InventoryHolder {
        private final UUID batteryId;
        private Inventory inventory;

        private Holder(UUID batteryId) {
            this.batteryId = batteryId;
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
        Holder holder = new Holder(battery.batteryId());
        holder.inventory = Bukkit.createInventory(holder, 45, messages.component("battery-ui.title"));
        fill(holder.inventory);
        holder.inventory.setItem(INFO_SLOT, infoItem(battery));
        for (int slot = 0; slot < battery.tier(); slot++) {
            CellRecord rune = battery.runes().get(slot);
            holder.inventory.setItem(RUNE_SLOTS[slot], rune == null ? emptyRuneSlot() : runeItem(rune));
        }
        holder.inventory.setItem(UPGRADE_SLOT, upgradeItem(battery));
        holder.inventory.setItem(CLOSE_SLOT, closeItem());
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        int rawSlot = event.getRawSlot();
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            CellRecord cursorRune = CellItem.read(event.getCursor());
            CellRecord targetRune = CellItem.read(event.getCurrentItem());
            if (cursorRune != null && targetRune != null
                    && cursorRune.type().isBatteryRune()
                    && cursorRune.type() == targetRune.type()
                    && cursorRune.tier() == targetRune.tier()
                    && event.getCursor().getAmount() == 1
                    && event.getCurrentItem().getAmount() == 1
                    && cursorRune.tier() < CellRecord.MAX_TIER) {
                CellRecord fused = cursorRune.fuse(targetRune);
                event.setCancelled(true);
                if (fused != null && batteries.canFuseRuneTier(player, fused.tier())) {
                    player.setItemOnCursor(CellItem.create(fused, batteries.baseIntervalTicks(), messages));
                    event.getClickedInventory().setItem(event.getSlot(), null);
                    messages.send(player, "battery-rune-fused", Map.of(
                            "rune", fused.type().displayName(messages),
                            "tier", Integer.toString(fused.tier())));
                }
                return;
            }
            if (event.isShiftClick()) {
                event.setCancelled(true);
                CellRecord rune = CellItem.read(event.getCurrentItem());
                if (rune != null && rune.type().isBatteryRune()) {
                    batteries.installFirstAvailableRune(player, holder.batteryId, event.getSlot(), rune);
                    reopen(player, holder.batteryId);
                }
            }
            return;
        }
        event.setCancelled(true);
        if (rawSlot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        for (int slot = 0; slot < RUNE_SLOTS.length; slot++) {
            if (rawSlot == RUNE_SLOTS[slot]) {
                batteries.removeRune(player, holder.batteryId, slot);
                reopen(player, holder.batteryId);
                return;
            }
        }
        if (rawSlot == UPGRADE_SLOT) {
            batteries.upgrade(player, holder.batteryId);
            reopen(player, holder.batteryId);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void reopen(Player player, UUID batteryId) {
        BatteryRecord battery = batteries.record(batteryId);
        if (battery == null) {
            player.closeInventory();
        } else {
            open(player, battery);
        }
    }

    private ItemStack infoItem(BatteryRecord battery) {
        ItemStack item = new ItemStack(Material.COPPER_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.info-name"));
        CoreRecord core = batteries.core(battery.islandIndex());
        double capacity = core == null ? 0 : batteries.capacity(battery, core);
        meta.lore(List.of(messages.parseComponent(messages.formatText("battery-ui.capacity", Map.of(
                "energy", format(battery.energy()),
                "capacity", format(capacity)))),
                messages.parseComponent(messages.formatText("battery-ui.tier", Map.of(
                        "tier", Integer.toString(battery.tier())))),
                messages.parseComponent(messages.formatText("battery-ui.offline", Map.of(
                        "hours", formatHours(batteries.maximumOfflineHours(battery.islandIndex())))))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyRuneSlot() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.empty-rune"));
        meta.lore(List.of(messages.component("battery-ui.install-rune")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack runeItem(CellRecord rune) {
        ItemStack item = new ItemStack(rune.type().itemMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(rune.type().color()
                + messages.legacy("cell-item.name-rune") + rune.type().displayName(messages)
                + " T" + rune.tier()));
        meta.lore(List.of(messages.component("battery-ui.remove-rune")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(BatteryRecord battery) {
        boolean maxed = battery.tier() >= 3;
        ItemStack item = new ItemStack(maxed ? Material.NETHER_STAR : Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component(maxed ? "battery-ui.max-tier" : "battery-ui.upgrade-name"));
        if (maxed) {
            meta.lore(List.of(messages.component("battery-ui.max-tier-lore")));
        } else {
            meta.lore(List.of(messages.parseComponent(messages.formatText("battery-ui.upgrade-lore", Map.of(
                    "tier", Integer.toString(battery.tier() + 1),
                    "cost", format(batteries.upgradeCost(battery.tier() + 1)),
                    "level", Integer.toString(batteries.upgradeRequiredLevel(battery.tier() + 1)))))));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("battery-ui.close"));
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot < 9 || slot >= 36 || slot % 9 == 0 || slot % 9 == 8) {
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
