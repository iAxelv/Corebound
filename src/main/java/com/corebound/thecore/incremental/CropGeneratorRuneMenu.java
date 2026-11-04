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

public final class CropGeneratorRuneMenu implements Listener {
    private static final int PROGRESSION_SLOT = 22;
    private static final int BACK_SLOT = 26;

    private static final class Holder implements InventoryHolder {
        private final UUID generatorId;
        private Inventory inventory;

        private Holder(UUID generatorId) {
            this.generatorId = generatorId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Messages messages;
    private final CropGeneratorService generators;
    private final PylonService pylons;

    public CropGeneratorRuneMenu(Messages messages, CropGeneratorService generators, PylonService pylons) {
        this.messages = messages;
        this.generators = generators;
        this.pylons = pylons;
    }

    public void open(Player player, CropGeneratorRecord generator) {
        Holder holder = new Holder(generator.generatorId());
        holder.inventory = Bukkit.createInventory(
                holder, 27, messages.parseComponent(generators.menuText("runes.title")));
        fill(holder.inventory);
        int[] slots = generators.runeMenuSlots(generator.runeSlots());
        for (int index = 0; index < slots.length; index++) {
            CellRecord rune = generator.runes().get(index);
            holder.inventory.setItem(
                    slots[index], rune == null ? emptyRuneSlot()
                            : CellItem.create(rune, pylons.baseIntervalTicks(), messages));
        }
        holder.inventory.setItem(4, statisticsItem(generator));
        holder.inventory.setItem(
                generators.menuSlot("runes.progression-slot", PROGRESSION_SLOT), progressionItem(generator));
        holder.inventory.setItem(generators.menuSlot("runes.back-slot", BACK_SLOT), backItem());
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        CropGeneratorRecord generator = generators.record(holder.generatorId);
        if (generator == null || !generators.canManage(player, generator)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                installFromInventory(player, event, generator, holder);
            } else {
                fuseInventoryRunes(player, event, generator);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == generators.menuSlot("runes.back-slot", BACK_SLOT)) {
            generators.openMenu(player, generator.generatorId());
            return;
        }
        int runeIndex = runeIndex(event.getRawSlot(), generator.runeSlots());
        if (runeIndex >= 0 && generator.runes().containsKey(runeIndex)) {
            if (!generators.canRemoveRunes(player, generator)) {
                return;
            }
            ItemStack rune = CellItem.create(
                    generator.runes().get(runeIndex), pylons.baseIntervalTicks(), messages);
            if (!giveItem(player, rune)) {
                return;
            }
            generators.setRune(generator.generatorId(), runeIndex, null);
            reopen(player, holder.generatorId);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void installFromInventory(
            Player player, InventoryClickEvent event, CropGeneratorRecord generator, Holder holder) {
        ItemStack item = event.getCurrentItem();
        CellRecord rune = CellItem.read(item);
        if (rune == null || item.getAmount() != 1) {
            return;
        }
        if (!rune.type().isCropGeneratorRune()) {
            messages.send(player, "crop-generator-rune-wrong-type");
            return;
        }
        if (!generators.canUseRuneTier(player, generator, rune.tier())) {
            return;
        }
        int index = firstEmptyRune(generator);
        if (index < 0) {
            return;
        }
        if (generators.setRune(generator.generatorId(), index, rune)) {
            event.getClickedInventory().setItem(event.getSlot(), null);
            reopen(player, holder.generatorId);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.5f);
        }
    }

    private void fuseInventoryRunes(Player player, InventoryClickEvent event, CropGeneratorRecord generator) {
        if (event.getClickedInventory() == null) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();
        CellRecord first = CellItem.read(cursor);
        CellRecord second = CellItem.read(target);
        if (first == null || second == null || !first.type().isCropGeneratorRune()
                || first.type() != second.type() || cursor.getAmount() != 1 || target.getAmount() != 1) {
            return;
        }
        CellRecord fused = first.fuse(second);
        if (fused == null || !generators.canUseRuneTier(player, generator, fused.tier())) {
            return;
        }
        event.setCancelled(true);
        player.setItemOnCursor(CellItem.create(fused, pylons.baseIntervalTicks(), messages));
        event.getClickedInventory().setItem(event.getSlot(), null);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.8f);
        messages.send(player, "crop-generator-rune-fused", Map.of(
                "cell", first.type().displayName(messages),
                "tier", Integer.toString(fused.tier())));
    }

    private void reopen(Player player, UUID generatorId) {
        CropGeneratorRecord updated = generators.record(generatorId);
        if (updated == null) {
            player.closeInventory();
        } else {
            open(player, updated);
        }
    }

    private int firstEmptyRune(CropGeneratorRecord generator) {
        for (int index = 0; index < generator.runeSlots(); index++) {
            if (!generator.runes().containsKey(index)) {
                return index;
            }
        }
        return -1;
    }

    private int runeIndex(int rawSlot, int unlockedSlots) {
        int[] slots = generators.runeMenuSlots(unlockedSlots);
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == rawSlot) {
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
        ItemStack item = new ItemStack(generators.menuMaterial("runes.empty-slot.material", "GREEN_STAINED_GLASS_PANE"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(generators.menuText("runes.empty-slot.name")));
        meta.lore(generators.menuLore("runes.empty-slot.lore").stream()
                .map(messages::parseComponent)
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statisticsItem(CropGeneratorRecord generator) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("crop-generator-ui.runes-title"));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("crop-generator-ui.runes-equipped", Map.of(
                        "active", Integer.toString(generator.activeRunes().size()),
                        "slots", Integer.toString(generator.runeSlots())))),
                messages.parseComponent(messages.formatText("crop-generator-ui.runes-level", Map.of(
                        "level", Integer.toString(generator.level()))))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack progressionItem(CropGeneratorRecord generator) {
        int requiredTwo = generators.runeSlotRequiredLevel(2);
        int requiredThree = generators.runeSlotRequiredLevel(3);
        boolean maxed = generator.runeSlots() >= 3;
        boolean unlocked = generator.level() >= (generator.runeSlots() == 1 ? requiredTwo : requiredThree);
        String materialPath = maxed
                ? "runes.progression.max-material"
                : unlocked ? "runes.progression.unlocked-material" : "runes.progression.locked-material";
        String materialDefault = maxed ? "NETHER_STAR" : unlocked ? "ANVIL" : "BARRIER";
        ItemStack item = new ItemStack(generators.menuMaterial(materialPath, materialDefault));
        ItemMeta meta = item.getItemMeta();
        String namePath = maxed
                ? "runes.progression.max-name"
                : unlocked ? "runes.progression.unlocked-name" : "runes.progression.locked-name";
        meta.displayName(messages.parseComponent(generators.menuText(namePath)));
        String lorePath = maxed
                ? "runes.progression.max-lore"
                : unlocked ? "runes.progression.unlocked-lore" : "runes.progression.locked-lore";
        int required = generator.runeSlots() == 1 ? requiredTwo : requiredThree;
        Map<String, String> placeholders = Map.of(
                "%required_level%", Integer.toString(required),
                "%slots_current%", Integer.toString(generator.runeSlots()),
                "%slots_next%", Integer.toString(Math.min(3, generator.runeSlots() + 1)));
        meta.lore(generators.menuLore(lorePath).stream()
                .map(line -> replace(line, placeholders))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backItem() {
        ItemStack item = new ItemStack(generators.menuMaterial("runes.back.material", "ARROW"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(
                generators.menuText("runes.back.name")));
        item.setItemMeta(meta);
        return item;
    }

    private Component replace(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return messages.parseComponent(result);
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
