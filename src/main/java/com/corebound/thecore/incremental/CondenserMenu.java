package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.ArrayList;
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

public final class CondenserMenu implements Listener {
    private static final int INFO_SLOT = 4;
    private static final int STATE_SLOT = 20;
    private static final int RUNES_SLOT = 22;
    private static final int UPGRADE_SLOT = 24;
    private static final int CLOSE_SLOT = 40;

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

    public CondenserMenu(Messages messages, CondenserService condensers) {
        this.messages = messages;
        this.condensers = condensers;
    }

    public void open(Player player, CondenserRecord condenser) {
        Holder holder = new Holder(condenser.condenserId());
        holder.inventory = Bukkit.createInventory(
                holder, 45, messages.component("condenser-ui.title"));
        fill(holder.inventory);
        holder.inventory.setItem(INFO_SLOT, infoItem(condenser));
        holder.inventory.setItem(STATE_SLOT, stateItem(condenser));
        holder.inventory.setItem(RUNES_SLOT, runesItem(condenser));
        holder.inventory.setItem(UPGRADE_SLOT, upgradeItem(condenser));
        holder.inventory.setItem(CLOSE_SLOT, closeItem());
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
        if (event.getRawSlot() == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (event.getRawSlot() == RUNES_SLOT) {
            condensers.openRuneMenu(player, holder.condenserId);
            return;
        }
        if (event.getRawSlot() == STATE_SLOT) {
            condensers.toggleEnabled(player, holder.condenserId);
        } else if (event.getRawSlot() == UPGRADE_SLOT) {
            condensers.upgrade(player, holder.condenserId);
        }
        CondenserRecord condenser = condensers.record(holder.condenserId);
        if (condenser == null) {
            player.closeInventory();
        } else if (event.getRawSlot() == STATE_SLOT || event.getRawSlot() == UPGRADE_SLOT) {
            open(player, condenser);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private ItemStack infoItem(CondenserRecord condenser) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(messages.formatText("condenser-ui.info-name", Map.of(
                "level", Integer.toString(condenser.level()),
                "max_level", Integer.toString(condensers.maximumLevel())))));
        List<Component> lore = new ArrayList<>();
        String generationCost = messages.formatText("condenser-ui.generation-cost", Map.of(
                "cost", format(condensers.energyCost(condenser))));
        lore.add(messages.parseComponent(generationCost));
        lore.add(Component.empty());
        lore.add(messages.component("condenser-ui.possible-yields"));
        for (Material material : condensers.unlockedMaterials(condenser.level())) {
            String yieldLine = messages.formatText("condenser-ui.yield-line", Map.of(
                    "material", messages.text(condensers.oreMessageKey(material)),
                    "chance", format(condensers.materialChance(condenser, material))));
            lore.add(messages.parseComponent(yieldLine));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stateItem(CondenserRecord condenser) {
        ItemStack item = new ItemStack(Material.LEVER);
        ItemMeta meta = item.getItemMeta();
        String stateColor = switch (condenser.state()) {
            case ACTIVE -> "&a";
            case IDLE -> "&e";
            case DISABLED -> "&c";
        };
        meta.displayName(messages.component("condenser-ui.state-name"));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("condenser-ui.state-line", Map.of(
                        "color", stateColor,
                        "state", messages.text("condenser-ui.state-" + condenser.state().name().toLowerCase(
                                java.util.Locale.ROOT))))),
                Component.empty(),
                messages.component(condenser.state() == CondenserState.DISABLED
                        ? "condenser-ui.enable"
                        : "condenser-ui.disable")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack runesItem(CondenserRecord condenser) {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("condenser-ui.runes-name"));
        meta.lore(List.of(
                messages.parseComponent(messages.formatText("condenser-ui.rune-slots", Map.of(
                        "active", Integer.toString(condenser.activeRunes().size()),
                        "slots", Integer.toString(condenser.runeSlots())))),
                messages.component("condenser-ui.runes-description"),
                Component.empty(),
                messages.component("condenser-ui.manage-runes")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(CondenserRecord condenser) {
        boolean maxed = condenser.level() >= condensers.maximumLevel();
        ItemStack item = new ItemStack(maxed ? Material.NETHER_STAR : Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        String title = maxed
                ? messages.legacy("condenser-ui.max-level")
                : messages.formatText("condenser-ui.upgrade-level", Map.of(
                        "level", Integer.toString(condenser.level() + 1)));
        meta.displayName(messages.parseComponent(title));
        List<Component> lore = maxed
                ? List.of(messages.component("condenser-ui.max-level-lore"))
                : List.of(
                        messages.parseComponent(messages.formatText("condenser-ui.upgrade-cost", Map.of(
                                "cost", format(condensers.upgradeCost(condenser.level()))))),
                        messages.parseComponent(messages.formatText("condenser-ui.next-generation-cost", Map.of(
                                "cost", format(condensers.energyCost(condenser.withLevel(
                                        condenser.level() + 1,
                                        condensers.maximumRuneSlotsForLevel(condenser.level() + 1))))))),
                        Component.empty(),
                        messages.component("condenser-ui.click-upgrade"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.component("condenser-ui.close"));
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

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
