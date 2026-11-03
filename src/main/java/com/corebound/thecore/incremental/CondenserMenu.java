package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.List;
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
    private static final int STATE_SLOT = 11;
    private static final int INFO_SLOT = 13;
    private static final int UPGRADE_SLOT = 15;
    private static final int CLOSE_SLOT = 22;

    private static final class Holder implements InventoryHolder {
        private final java.util.UUID condenserId;
        private Inventory inventory;

        private Holder(java.util.UUID condenserId) {
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
                holder, 27, messages.parseComponent("&5Condensador de Materia"));
        holder.inventory.setItem(STATE_SLOT, stateItem(condenser));
        holder.inventory.setItem(INFO_SLOT, infoItem(condenser));
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
        boolean updated = switch (event.getRawSlot()) {
            case STATE_SLOT -> condensers.toggleEnabled(player, holder.condenserId);
            case UPGRADE_SLOT -> condensers.upgrade(player, holder.condenserId);
            default -> false;
        };
        CondenserRecord condenser = condensers.record(holder.condenserId);
        if (condenser == null) {
            player.closeInventory();
        } else if (updated || event.getRawSlot() == STATE_SLOT || event.getRawSlot() == UPGRADE_SLOT) {
            open(player, condenser);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private ItemStack stateItem(CondenserRecord condenser) {
        Material material = switch (condenser.state()) {
            case ACTIVE -> Material.LIME_DYE;
            case IDLE -> Material.YELLOW_DYE;
            case DISABLED -> Material.RED_DYE;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&bEstado: &f" + condenser.state().name()));
        meta.lore(List.of(
                messages.parseComponent("&7El condensador se pausa automáticamente"),
                messages.parseComponent("&7si el Núcleo no tiene energía suficiente."),
                Component.empty(),
                messages.parseComponent(condenser.state() == CondenserState.DISABLED
                        ? "&eHaz clic para activarlo."
                        : "&eHaz clic para desactivarlo.")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem(CondenserRecord condenser) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&dInformación del Condensador"));
        meta.lore(List.of(
                messages.parseComponent("&7Nivel: &e" + condenser.level() + "/" + condensers.maximumLevel()),
                messages.parseComponent("&7Probabilidad de mineral: &b"
                        + String.format(java.util.Locale.ROOT, "%.0f", condensers.oreChance() * 100) + "%"),
                messages.parseComponent("&7Consumo por generación: &6"
                        + String.format(java.util.Locale.ROOT, "%.1f", condensers.energyCost(condenser.level()))
                        + "⚡"),
                messages.parseComponent("&7Los minerales disponibles aumentan con cada nivel.")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(CondenserRecord condenser) {
        boolean maxed = condenser.level() >= condensers.maximumLevel();
        ItemStack item = new ItemStack(maxed ? Material.NETHER_STAR : Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(maxed
                ? "&aNivel máximo alcanzado"
                : "&eMejorar al nivel " + (condenser.level() + 1)));
        List<Component> lore = maxed
                ? List.of(messages.parseComponent("&7Ya están desbloqueados todos los minerales."))
                : List.of(
                        messages.parseComponent("&7Costo: &6"
                                + String.format(java.util.Locale.ROOT, "%.1f",
                                        condensers.upgradeCost(condenser.level()))
                                + "⚡"),
                        messages.parseComponent("&7Desbloquea un nuevo mineral."),
                        messages.parseComponent("&7Consumo siguiente: &e"
                                + String.format(java.util.Locale.ROOT, "%.1f",
                                        condensers.energyCost(condenser.level() + 1))
                                + "⚡/generación"),
                        Component.empty(),
                        messages.parseComponent("&eHaz clic para mejorar."));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent("&cCerrar"));
        item.setItemMeta(meta);
        return item;
    }
}
