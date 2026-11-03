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

public final class PylonMenu implements Listener {
    private static final int UPGRADE_SLOT = 22;
    private static final int[] FORGE_SLOTS = {10, 13, 16};
    private static final int FORGE_REFRESH_SLOT = 22;

    private static final class Holder implements InventoryHolder {
        private final UUID pylonId;
        private final long islandIndex;
        private final boolean forge;
        private CellType[] offers;
        private final boolean[] available;
        private Inventory inventory;

        private Holder(UUID pylonId, long islandIndex, boolean forge, CellType[] offers, boolean[] available) {
            this.pylonId = pylonId;
            this.islandIndex = islandIndex;
            this.forge = forge;
            this.offers = offers;
            this.available = available;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Messages messages;
    private final PylonService pylons;

    public PylonMenu(Messages messages, PylonService pylons) {
        this.messages = messages;
        this.pylons = pylons;
    }

    public void openPylon(Player player, PylonRecord pylon) {
        Holder holder = new Holder(pylon.pylonId(), pylon.islandIndex(), false, null, null);
        holder.inventory = Bukkit.createInventory(
                holder, 27, messages.parseComponent("&5Gestión del Pilón"));
        fillFrame(holder.inventory, pylonCellSlots(pylon.maxSlots()));
        int[] slots = pylonCellSlots(pylon.maxSlots());
        for (int index = 0; index < slots.length; index++) {
            CellRecord cell = pylon.equippedCells().get(index);
            if (cell != null) {
                holder.inventory.setItem(slots[index], CellItem.create(cell, pylons.baseIntervalTicks()));
            } else {
                holder.inventory.setItem(slots[index], emptyCellSlotItem());
            }
        }
        holder.inventory.setItem(4, statisticsItem(pylon));
        holder.inventory.setItem(UPGRADE_SLOT, upgradeItem(pylon));
        player.openInventory(holder.inventory);
    }

    public void openForge(Player player, long islandIndex) {
        Holder holder = new Holder(
                null,
                islandIndex,
                true,
                pylons.cellForgeOffers(islandIndex),
                pylons.availableCellForgeOffers(islandIndex));
        holder.inventory = Bukkit.createInventory(
                holder, 27, messages.parseComponent("&5Forja Elemental"));
        renderForge(holder);
        player.openInventory(holder.inventory);
    }

    private void renderForge(Holder holder) {
        fillFrame(holder.inventory, FORGE_SLOTS);
        for (int index = 0; index < FORGE_SLOTS.length; index++) {
            holder.inventory.setItem(
                    FORGE_SLOTS[index], holder.available[index] ? forgeItem(holder.offers[index]) : null);
        }
        holder.inventory.setItem(FORGE_REFRESH_SLOT, refreshItem());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            fuseInventoryCells(event);
            return;
        }
        int rawSlot = event.getRawSlot();
        if (holder.forge) {
            if (event.getClickedInventory() != event.getView().getTopInventory()) {
                if (event.isShiftClick()) {
                    event.setCancelled(true);
                } else {
                    fuseInventoryCells(event);
                }
                return;
            }
            event.setCancelled(true);
            if (rawSlot == FORGE_REFRESH_SLOT) {
                CellType[] refreshed = pylons.refreshCellForge(player, holder.islandIndex);
                if (refreshed != null) {
                    holder.offers = refreshed;
                    System.arraycopy(
                            pylons.availableCellForgeOffers(holder.islandIndex),
                            0,
                            holder.available,
                            0,
                            holder.available.length);
                    renderForge(holder);
                }
                return;
            }
            for (int index = 0; index < FORGE_SLOTS.length; index++) {
                if (rawSlot == FORGE_SLOTS[index] && holder.available[index]) {
                    if (pylons.forgeCell(player, holder.islandIndex, holder.offers[index])) {
                        pylons.consumeCellForgeOffer(holder.islandIndex, index);
                        holder.available[index] = false;
                        holder.inventory.setItem(rawSlot, null);
                    }
                    return;
                }
            }
            return;
        }

        PylonRecord pylon = pylons.getPylon(holder.pylonId);
        if (pylon == null || !pylons.canManagePylon(player, pylon)) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                equipCellFromInventory(player, event, holder.inventory, pylon);
            } else {
                fuseInventoryCells(event);
            }
            return;
        }

        event.setCancelled(true);
        if (rawSlot == UPGRADE_SLOT) {
            pylons.upgradePylon(player, holder.pylonId);
            PylonRecord updated = pylons.getPylon(holder.pylonId);
            if (updated != null) {
                openPylon(player, updated);
            }
            return;
        }
        int cellIndex = cellIndexAt(rawSlot, pylon);
        if (cellIndex >= 0) {
            withdrawCell(player, holder.inventory, pylon, cellIndex);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void equipCellFromInventory(
            Player player, InventoryClickEvent event, Inventory menu, PylonRecord pylon) {
        ItemStack item = event.getCurrentItem();
        CellRecord cell = CellItem.read(item);
        if (cell == null || item.getAmount() != 1) {
            return;
        }
        if (!pylons.canUseCellTier(player, pylon.islandIndex(), cell.tier())) {
            return;
        }
        int cellIndex = firstEmptyCellSlot(pylon);
        if (cellIndex < 0) {
            return;
        }
        int[] slots = pylonCellSlots(pylon.maxSlots());
        int menuSlot = slots[cellIndex];
        pylons.setEquippedCell(pylon.pylonId(), cellIndex, cell);
        PylonRecord updated = pylons.getPylon(pylon.pylonId());
        event.getClickedInventory().setItem(event.getSlot(), null);
        menu.setItem(menuSlot, CellItem.create(cell, pylons.baseIntervalTicks()));
        if (updated != null) {
            menu.setItem(4, statisticsItem(updated));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
    }

    private void withdrawCell(Player player, Inventory menu, PylonRecord pylon, int cellIndex) {
        CellRecord cell = pylon.equippedCells().get(cellIndex);
        if (cell == null) {
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            messages.send(player, "pylon-cell-inventory-full");
            return;
        }
        pylons.setEquippedCell(pylon.pylonId(), cellIndex, null);
        ItemStack cellItem = CellItem.create(cell, pylons.baseIntervalTicks());
        if (!player.getInventory().addItem(cellItem).isEmpty()) {
            pylons.setEquippedCell(pylon.pylonId(), cellIndex, cell);
            messages.send(player, "pylon-cell-inventory-full");
            return;
        }
        menu.setItem(pylonCellSlots(pylon.maxSlots())[cellIndex], null);
        PylonRecord updated = pylons.getPylon(pylon.pylonId());
        if (updated != null) {
            menu.setItem(4, statisticsItem(updated));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
    }

    private int firstEmptyCellSlot(PylonRecord pylon) {
        for (int slot = 0; slot < pylon.maxSlots(); slot++) {
            if (!pylon.equippedCells().containsKey(slot)) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack emptyCellSlotItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&bRanura de célula"));
        meta.lore(List.of(
                messages.parseComponent("&7Mayús+clic en una célula de tu inventario"),
                messages.parseComponent("&7para instalarla."),
                Component.empty(),
                messages.parseComponent("&7Haz clic en una célula instalada"),
                messages.parseComponent("&7para devolverla a tu inventario.")));
        item.setItemMeta(meta);
        return item;
    }

    private void fuseInventoryCells(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() == null
                || event.getClickedInventory() == event.getView().getTopInventory()) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();
        CellRecord cursorCell = CellItem.read(cursor);
        CellRecord targetCell = CellItem.read(target);
        if (cursorCell == null || targetCell == null) {
            return;
        }
        if (cursor.getAmount() != 1 || target.getAmount() != 1
                || cursorCell.type() != targetCell.type()
                || cursorCell.tier() != targetCell.tier()
                || cursorCell.tier() >= CellRecord.MAX_TIER) {
            return;
        }
        CellRecord fusedCell = cursorCell.fuse(targetCell);
        if (fusedCell == null || !pylons.canFuseCellTier(player, fusedCell.tier())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        player.setItemOnCursor(CellItem.create(fusedCell, pylons.baseIntervalTicks()));
        event.getClickedInventory().setItem(event.getSlot(), null);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
        messages.send(player, "cell-fused", Map.of(
                "cell", cursorCell.type().displayName(),
                "tier", Integer.toString(fusedCell.tier())));
    }

    private ItemStack statisticsItem(PylonRecord pylon) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&bEstadísticas del Pilón"));
        CoreRecord core = pylons.coreRecord(pylon.islandIndex());
        List<Component> lore;
        if (core == null) {
            lore = List.of(messages.parseComponent("&cNo se encontró el Núcleo de esta isla."));
        } else {
            double interval = pylons.effectiveIntervalTicks(pylon) / 20.0;
            double yield = pylons.estimatedPulseYield(core, pylon);
            double perMinute = yield * 60.0 / interval;
            lore = List.of(
                    messages.parseComponent("&7Intervalo: &f" + formatEnergy(interval) + "s"),
                    messages.parseComponent("&7Energía promedio por pulso: &e+" + formatEnergy(yield) + "⚡"),
                    messages.parseComponent("&7Producción promedio: &a+" + formatEnergy(perMinute) + "⚡/min"),
                    messages.parseComponent("&7Tier de runa permitido: &bT"
                            + CellRecord.maximumTierForCoreLevel(core.level())));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(PylonRecord pylon) {
        ItemStack item;
        String title;
        List<String> lore;
        if (pylon.tier() >= 3) {
            item = new ItemStack(Material.NETHER_STAR);
            title = "&aPilón al máximo";
            lore = List.of("&7Tier máximo alcanzado.", "&7Ranuras: &e" + pylon.maxSlots());
        } else {
            int nextTier = pylon.tier() + 1;
            int requiredCoreLevel = pylons.pylonUpgradeRequiredCoreLevel(nextTier);
            double cost = pylons.pylonUpgradeCost(nextTier);
            if (pylons.coreLevel(pylon.islandIndex()) < requiredCoreLevel) {
                item = new ItemStack(Material.BARRIER);
                title = "&cMejora bloqueada &7(T" + nextTier + ")";
                lore = List.of(
                        "&cRequiere Núcleo nivel &e" + requiredCoreLevel + "&c.",
                        "&7Ranuras al mejorar: &e" + pylon.maxSlots() + " &7→ &e" + nextTier);
            } else {
                item = new ItemStack(Material.ANVIL);
                title = "&aMejorar Pilón &7(T" + nextTier + ")";
                lore = List.of(
                        "&7Costo: &6" + formatEnergy(cost) + "⚡",
                        "&7Ranuras: &e" + pylon.maxSlots() + " &7→ &e" + nextTier,
                        "",
                        "&eHaz clic para mejorar.");
            }
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(title));
        meta.lore(lore.stream().map(messages::parseComponent).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack forgeItem(CellType type) {
        ItemStack item = new ItemStack(type.itemMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(type.color() + "Forjar Célula " + type.displayName()));
        List<Component> lore = CellItem.cellLore(new CellRecord(type, 1), pylons.baseIntervalTicks()).stream()
                .map(messages::parseComponent)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        lore.add(Component.empty());
        lore.add(messages.parseComponent("&7Costo: &6" + formatEnergy(pylons.cellForgeCost(type)) + "⚡"));
        lore.add(messages.parseComponent("&eHaz clic para comprar esta oferta."));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack refreshItem() {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&dActualizar ofertas"));
        meta.lore(List.of(
                messages.parseComponent("&7Vuelve a sortear las tres ofertas."),
                messages.parseComponent("&7Pueden salir células repetidas."),
                Component.empty(),
                messages.parseComponent("&7Costo: &6" + formatEnergy(pylons.forgeRefreshCost()) + "⚡"),
                messages.parseComponent("&eHaz clic para actualizar.")));
        item.setItemMeta(meta);
        return item;
    }

    private void fillFrame(Inventory inventory, int[] openSlots) {
        ItemStack pane = new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!contains(openSlots, slot) && slot != UPGRADE_SLOT) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private int[] pylonCellSlots(int tier) {
        return switch (tier) {
            case 1 -> new int[] {13};
            case 2 -> new int[] {12, 14};
            default -> new int[] {11, 13, 15};
        };
    }

    private int cellIndexAt(int slot, PylonRecord pylon) {
        if (pylon == null) {
            return -1;
        }
        int[] slots = pylonCellSlots(pylon.maxSlots());
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == slot) {
                return index;
            }
        }
        return -1;
    }

    private boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private String formatEnergy(double energy) {
        return String.format(java.util.Locale.ROOT, "%.1f", energy);
    }
}
