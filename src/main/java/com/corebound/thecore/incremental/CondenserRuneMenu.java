package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.List;
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

public final class CondenserRuneMenu implements Listener {
    private static final int[] RUNE_SLOTS = {10, 13, 16};
    private static final int UPGRADE_SLOT = 22;
    private static final int BACK_SLOT = 26;

    private static final class Holder implements InventoryHolder {
        private final UUID condenserId;
        private Inventory inventory;

        private Holder(UUID condenserId) {
            this.condenserId = condenserId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Messages messages;
    private final CondenserService condensers;
    private final PylonService pylons;

    public CondenserRuneMenu(Messages messages, CondenserService condensers, PylonService pylons) {
        this.messages = messages;
        this.condensers = condensers;
        this.pylons = pylons;
    }

    public void open(Player player, CondenserRecord condenser) {
        Holder holder = new Holder(condenser.condenserId());
        holder.inventory = Bukkit.createInventory(
                holder, 27, messages.parseComponent("&5Runas del Condensador"));
        fill(holder.inventory);
        for (int index = 0; index < condenser.runeSlots(); index++) {
            CellRecord rune = condenser.runes().get(index);
            holder.inventory.setItem(
                    RUNE_SLOTS[index],
                    rune == null ? emptyRuneSlot() : CellItem.create(rune, pylons.baseIntervalTicks()));
        }
        holder.inventory.setItem(UPGRADE_SLOT, upgradeItem(condenser));
        holder.inventory.setItem(BACK_SLOT, backItem());
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        CondenserRecord condenser = condensers.record(holder.condenserId);
        if (condenser == null || !condensers.canManage(player, condenser)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                installFromInventory(player, event, condenser, holder);
            } else {
                fuseInventoryRunes(player, event, condenser);
            }
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot == BACK_SLOT) {
            condensers.openMenu(player, condenser.condenserId());
            return;
        }
        if (rawSlot == UPGRADE_SLOT) {
            condensers.upgradeRuneSlots(player, condenser.condenserId());
            reopen(player, holder.condenserId);
            return;
        }
        int runeIndex = runeIndex(rawSlot);
        if (runeIndex >= 0 && runeIndex < condenser.runeSlots()
                && condenser.runes().containsKey(runeIndex)) {
            if (!condensers.canRemoveRunes(player, condenser)) {
                return;
            }
            ItemStack rune = CellItem.create(
                    condenser.runes().get(runeIndex), pylons.baseIntervalTicks());
            if (!giveItem(player, rune)) {
                return;
            }
            condensers.setRune(condenser.condenserId(), runeIndex, null);
            reopen(player, holder.condenserId);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void installFromInventory(
            Player player, InventoryClickEvent event, CondenserRecord condenser, Holder holder) {
        ItemStack item = event.getCurrentItem();
        CellRecord rune = CellItem.read(item);
        if (rune == null || item.getAmount() != 1) {
            return;
        }
        if (!rune.type().isCondenserRune()) {
            messages.send(player, "condenser-rune-wrong-type");
            return;
        }
        if (!condensers.canUseRuneTier(player, condenser, rune.tier())) {
            return;
        }
        int index = firstEmptyRune(condenser);
        if (index < 0) {
            return;
        }
        if (condensers.setRune(condenser.condenserId(), index, rune)) {
            event.getClickedInventory().setItem(event.getSlot(), null);
            reopen(player, holder.condenserId);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
        }
    }

    private void fuseInventoryRunes(Player player, InventoryClickEvent event, CondenserRecord condenser) {
        if (event.getClickedInventory() == null) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();
        CellRecord first = CellItem.read(cursor);
        CellRecord second = CellItem.read(target);
        if (first == null || second == null || !first.type().isCondenserRune()
                || first.type() != second.type() || cursor.getAmount() != 1 || target.getAmount() != 1) {
            return;
        }
        CellRecord fused = first.fuse(second);
        if (fused == null || !condensers.canUseRuneTier(player, condenser, fused.tier())) {
            return;
        }
        event.setCancelled(true);
        player.setItemOnCursor(CellItem.create(fused, pylons.baseIntervalTicks()));
        event.getClickedInventory().setItem(event.getSlot(), null);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
        messages.send(player, "condenser-rune-fused", java.util.Map.of(
                "cell", first.type().displayName(),
                "tier", Integer.toString(fused.tier())));
    }

    private void reopen(Player player, UUID condenserId) {
        CondenserRecord updated = condensers.record(condenserId);
        if (updated == null) {
            player.closeInventory();
        } else {
            open(player, updated);
        }
    }

    private int firstEmptyRune(CondenserRecord condenser) {
        for (int index = 0; index < condenser.runeSlots(); index++) {
            if (!condenser.runes().containsKey(index)) {
                return index;
            }
        }
        return -1;
    }

    private int runeIndex(int rawSlot) {
        for (int index = 0; index < RUNE_SLOTS.length; index++) {
            if (RUNE_SLOTS[index] == rawSlot) {
                return index;
            }
        }
        return -1;
    }

    private boolean giveItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover).setPersistent(true);
        }
        return true;
    }

    private ItemStack emptyRuneSlot() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&bRanura de runa"));
        meta.lore(List.of(messages.parseComponent("&7Mayús+clic en una runa de tu inventario para instalarla.")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(CondenserRecord condenser) {
        int nextSlots = condenser.runeSlots() + 1;
        boolean maxed = condenser.runeSlots() >= RUNE_SLOTS.length;
        ItemStack item = new ItemStack(maxed ? Material.NETHER_STAR : Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(maxed
                ? "&aRanuras de runa al máximo"
                : "&eDesbloquear ranura " + nextSlots));
        List<Component> lore = maxed
                ? List.of(messages.parseComponent("&7El Condensador tiene todas sus ranuras."))
                : List.of(
                        messages.parseComponent("&7Requiere Condensador nivel "
                                + condensers.runeSlotRequiredLevel(nextSlots) + "."),
                        messages.parseComponent("&7Costo: &6"
                                + String.format(java.util.Locale.ROOT, "%.1f",
                                        condensers.runeSlotUpgradeCost(nextSlots))
                                + "⚡"),
                        Component.empty(),
                        messages.parseComponent("&eHaz clic para desbloquear."));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&cVolver al Condensador"));
        item.setItemMeta(meta);
        return item;
    }

    private void fill(Inventory inventory) {
        ItemStack pane = new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, pane);
        }
    }
}
