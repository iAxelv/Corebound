package com.corebound.thecore.incremental;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.message.Messages;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
        private boolean rolling;
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
    private final TheCorePlugin plugin;

    public PylonMenu(TheCorePlugin plugin, Messages messages, PylonService pylons) {
        this.plugin = plugin;
        this.messages = messages;
        this.pylons = pylons;
    }

    public void openPylon(Player player, PylonRecord pylon) {
        Holder holder = new Holder(pylon.pylonId(), pylon.islandIndex(), false, null, null);
        holder.inventory = Bukkit.createInventory(
                holder, 27, messages.component("pylon-ui.title"));
        fillFrame(holder.inventory, pylonCellSlots(pylon.maxSlots()));
        int[] slots = pylonCellSlots(pylon.maxSlots());
        for (int index = 0; index < slots.length; index++) {
            CellRecord cell = pylon.equippedCells().get(index);
            if (cell != null) {
                holder.inventory.setItem(slots[index], CellItem.create(cell, pylons.baseIntervalTicks(), messages));
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
                holder, 27, messages.component("pylon-ui.cell-forge-title"));
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
            if (holder.rolling) {
                event.setCancelled(true);
                return;
            }
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
                    startForgeRoulette(player, holder, refreshed);
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

    private void startForgeRoulette(Player player, Holder holder, CellType[] finalOffers) {
        holder.rolling = true;
        int baseDuration = pylons.forgeRouletteDurationTicks();
        int[] stopTicks = new int[FORGE_SLOTS.length];
        boolean[] stopped = new boolean[FORGE_SLOTS.length];
        for (int index = 0; index < stopTicks.length; index++) {
            int stagger = Math.max(1, baseDuration / 4);
            stopTicks[index] = baseDuration
                    + ThreadLocalRandom.current().nextInt(-stagger, stagger + 1);
        }
        int[] elapsed = {0};
        int[] lastRollSound = {-3};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != holder.inventory) {
                scheduled.cancel();
                holder.rolling = false;
                return;
            }
            elapsed[0]++;
            CellType[] interimOffers = pylons.randomCellOffers();
            boolean playedRollSound = false;
            for (int index = 0; index < FORGE_SLOTS.length; index++) {
                if (stopped[index]) {
                    continue;
                }
                if (elapsed[0] >= stopTicks[index]) {
                    stopped[index] = true;
                    holder.inventory.setItem(FORGE_SLOTS[index], forgeItem(finalOffers[index]));
                    pylons.playForgeRouletteStop(player, index);
                    continue;
                }
                int updateInterval = 1 + elapsed[0] / 8;
                if (elapsed[0] % updateInterval == 0) {
                    holder.inventory.setItem(FORGE_SLOTS[index], forgeItem(interimOffers[index]));
                    playedRollSound = true;
                }
            }
            if (playedRollSound && elapsed[0] - lastRollSound[0] >= 3) {
                pylons.playForgeRouletteRoll(player);
                lastRollSound[0] = elapsed[0];
            }
            if (allSlotsStopped(stopped)) {
                scheduled.cancel();
                holder.rolling = false;
            }
        }, () -> {
            holder.rolling = false;
        }, 1L, 1L);
        if (task == null) {
            holder.rolling = false;
            renderForge(holder);
            return;
        }
    }

    private boolean allSlotsStopped(boolean[] stopped) {
        for (boolean slotStopped : stopped) {
            if (!slotStopped) {
                return false;
            }
        }
        return true;
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
        if (!cell.type().isPylonCell()) {
            messages.send(player, "cell-wrong-device");
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
        menu.setItem(menuSlot, CellItem.create(cell, pylons.baseIntervalTicks(), messages));
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
        if (!pylons.canRemovePylonCells(player, pylon)) {
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            messages.send(player, "pylon-cell-inventory-full");
            return;
        }
        pylons.setEquippedCell(pylon.pylonId(), cellIndex, null);
        ItemStack cellItem = CellItem.create(cell, pylons.baseIntervalTicks(), messages);
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
        meta.displayName(messages.component("pylon-ui.empty-cell-name"));
        meta.lore(List.of(
                messages.component("pylon-ui.install-cell"),
                messages.component("pylon-ui.install-cell-next-line"),
                Component.empty(),
                messages.component("pylon-ui.remove-cell"),
                messages.component("pylon-ui.remove-cell-next-line")));
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
        if (!cursorCell.type().isPylonCell() || !targetCell.type().isPylonCell()) {
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
        player.setItemOnCursor(CellItem.create(fusedCell, pylons.baseIntervalTicks(), messages));
        event.getClickedInventory().setItem(event.getSlot(), null);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
        messages.send(player, "cell-fused", Map.of(
                "cell", cursorCell.type().displayName(messages),
                "tier", Integer.toString(fusedCell.tier())));
    }

    private ItemStack statisticsItem(PylonRecord pylon) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("pylon-ui.stats-name"));
        CoreRecord core = pylons.coreRecord(pylon.islandIndex());
        List<Component> lore;
        if (core == null) {
            lore = List.of(messages.component("pylon-ui.missing-core"));
        } else {
            double interval = pylons.effectiveIntervalTicks(pylon) / 20.0;
            double yield = pylons.estimatedPulseYield(core, pylon);
            double perMinute = yield * 60.0 / interval;
            lore = List.of(
                    messages.parseComponent(messages.formatText("pylon-ui.interval", Map.of(
                            "seconds", formatEnergy(interval)))),
                    messages.parseComponent(messages.formatText("pylon-ui.average-yield", Map.of(
                            "yield", formatEnergy(yield)))),
                    messages.parseComponent(messages.formatText("pylon-ui.average-production", Map.of(
                            "production", formatEnergy(perMinute)))),
                    messages.parseComponent(messages.formatText("pylon-ui.rune-tier", Map.of(
                            "tier", Integer.toString(CellRecord.maximumTierForCoreLevel(core.level()))))));
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
            title = messages.legacy("pylon-ui.max-upgrade");
            lore = List.of(
                    messages.legacy("pylon-ui.max-tier"),
                    messages.formatText("pylon-ui.slots", Map.of("slots", Integer.toString(pylon.maxSlots()))));
        } else {
            int nextTier = pylon.tier() + 1;
            int requiredCoreLevel = pylons.pylonUpgradeRequiredCoreLevel(nextTier);
            double cost = pylons.pylonUpgradeCost(nextTier);
            if (pylons.coreLevel(pylon.islandIndex()) < requiredCoreLevel) {
                item = new ItemStack(Material.BARRIER);
                title = messages.formatText("pylon-ui.locked-upgrade", Map.of(
                        "tier", Integer.toString(nextTier)));
                lore = List.of(
                        messages.formatText("pylon-ui.required-core", Map.of(
                                "level", Integer.toString(requiredCoreLevel))),
                        messages.formatText("pylon-ui.slots-upgrade", Map.of(
                                "current", Integer.toString(pylon.maxSlots()),
                                "next", Integer.toString(nextTier))));
            } else {
                item = new ItemStack(Material.ANVIL);
                title = messages.formatText("pylon-ui.upgrade", Map.of("tier", Integer.toString(nextTier)));
                lore = List.of(
                        messages.formatText("pylon-ui.cost", Map.of("cost", formatEnergy(cost))),
                        messages.formatText("pylon-ui.slots-upgrade", Map.of(
                                "current", Integer.toString(pylon.maxSlots()),
                                "next", Integer.toString(nextTier))),
                        "",
                        messages.legacy("pylon-ui.click-upgrade"));
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
        String itemName = messages.legacy(type.isRune() ? "pylon-ui.forge-rune" : "pylon-ui.forge-cell");
        meta.displayName(messages.parseComponent(type.color() + itemName + type.displayName(messages)));
        List<Component> lore = CellItem.cellLore(new CellRecord(type, 1), pylons.baseIntervalTicks(), messages).stream()
                .map(messages::parseComponent)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        lore.add(Component.empty());
        lore.add(messages.parseComponent(messages.formatText("pylon-ui.cost", Map.of(
                "cost", formatEnergy(pylons.cellForgeCost(type))))));
        lore.add(messages.component("pylon-ui.click-buy-cell"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack refreshItem() {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("pylon-ui.refresh"));
        meta.lore(List.of(
                messages.component("pylon-ui.refresh-description"),
                messages.component("pylon-ui.duplicate-cells"),
                Component.empty(),
                messages.parseComponent(messages.formatText("pylon-ui.cost", Map.of(
                        "cost", formatEnergy(pylons.forgeRefreshCost())))),
                messages.component("pylon-ui.click-refresh")));
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
