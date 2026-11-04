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

public final class CropGeneratorMenu implements Listener {
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

    public CropGeneratorMenu(Messages messages, CropGeneratorService generators) {
        this.messages = messages;
        this.generators = generators;
    }

    public void open(Player player, CropGeneratorRecord generator) {
        Holder holder = new Holder(generator.generatorId());
        holder.inventory = Bukkit.createInventory(
                holder, 45, messages.parseComponent(generators.menuText(
                        "main.title", "&2Catalizador de Cultivos")));
        fill(holder.inventory);
        holder.inventory.setItem(slot("info", 4), infoItem(generator));
        holder.inventory.setItem(slot("state", 20), stateItem(generator));
        holder.inventory.setItem(slot("runes", 22), runesItem(generator));
        holder.inventory.setItem(slot("upgrade", 24), upgradeItem(generator));
        holder.inventory.setItem(slot("close", 40), closeItem());
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot == slot("close", 40)) {
            player.closeInventory();
            return;
        }
        if (rawSlot == slot("runes", 22)) {
            generators.openRuneMenu(player, holder.generatorId);
            return;
        }
        if (rawSlot == slot("state", 20)) {
            generators.toggleEnabled(player, holder.generatorId);
        } else if (rawSlot == slot("upgrade", 24)) {
            generators.upgrade(player, holder.generatorId);
        }
        CropGeneratorRecord generator = generators.record(holder.generatorId);
        if (generator == null) {
            player.closeInventory();
        } else if (rawSlot == slot("state", 20) || rawSlot == slot("upgrade", 24)) {
            open(player, generator);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private ItemStack infoItem(CropGeneratorRecord generator) {
        ItemStack item = new ItemStack(material("main.info.material", "BOOK"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("main.info.name", "&aCatalizador &7[Nivel %level%/%max_level%]", Map.of(
                "%level%", Integer.toString(generator.level()),
                "%max_level%", Integer.toString(generators.maximumLevel()))));
        List<Component> lore = generators.menuLore("main.info.lore", List.of(
                        "&7Consumo: &6%energy_cost%⚡ &7cada &e%interval%s",
                        "&7Aceleración estimada: &a+%growth%%",
                        "&7Bonificación de cosecha: &e+%harvest%%")).stream()
                .map(line -> text(line, values(generator, generator.level())))
                .toList();
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stateItem(CropGeneratorRecord generator) {
        ItemStack item = new ItemStack(material("main.state.material", "LEVER"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("main.state.name", "&aEstado del generador", Map.of()));
        String stateKey = switch (generator.state()) {
            case ACTIVE -> "main.state.active";
            case IDLE -> "main.state.idle";
            case DISABLED -> "main.state.disabled";
        };
        String stateFallback = switch (generator.state()) {
            case ACTIVE -> "&aACTIVE";
            case IDLE -> "&eIDLE";
            case DISABLED -> "&cDISABLE";
        };
        String state = generators.menuText(stateKey, stateFallback);
        String actionKey = generator.state() == CondenserState.DISABLED
                ? "main.state.enable-lore"
                : "main.state.disable-lore";
        String actionFallback = generator.state() == CondenserState.DISABLED
                ? "&eHaz clic para activar."
                : "&eHaz clic para desactivar.";
        meta.lore(List.of(messages.parseComponent("&7Estado: " + state), Component.empty(),
                messages.parseComponent(generators.menuText(actionKey, actionFallback))));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack runesItem(CropGeneratorRecord generator) {
        ItemStack item = new ItemStack(material("main.runes.material", "GREEN_STAINED_GLASS_PANE"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("main.runes.name", "&aRunas de Cultivo", Map.of()));
        meta.lore(generators.menuLore("main.runes.lore", List.of(
                        "&7Ranuras: &e%runes%/%slots%",
                        "&7Mejoran la cosecha y reducen el consumo.",
                        "",
                        "&eHaz clic para administrar las runas.")).stream()
                .map(line -> text(line, Map.of(
                        "%runes%", Integer.toString(generator.activeRunes().size()),
                        "%slots%", Integer.toString(generator.runeSlots()))))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradeItem(CropGeneratorRecord generator) {
        boolean maxed = generator.level() >= generators.maximumLevel();
        ItemStack item = new ItemStack(material(
                maxed ? "main.upgrade.max-material" : "main.upgrade.material",
                maxed ? "NETHER_STAR" : "ANVIL"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(maxed ? "main.upgrade.max-name" : "main.upgrade.name",
                maxed ? "&aNivel máximo alcanzado" : "&eMejorar al nivel %next_level%",
                Map.of("%next_level%", Integer.toString(generator.level() + 1))));
        List<String> lore = generators.menuLore(
                maxed ? "main.upgrade.max-lore" : "main.upgrade.lore",
                maxed
                        ? List.of("&7El Catalizador ya alcanzó su nivel máximo.")
                        : List.of("&7Costo: &6%cost%⚡", "&7Aceleración al mejorar: &a+%growth%%", "",
                                "&eHaz clic para mejorar."));
        int displayedGrowthLevel = maxed ? generator.level() : generator.level() + 1;
        meta.lore(lore.stream().map(line -> text(line, values(generator, displayedGrowthLevel))).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(material("main.close.material", "ARROW"));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text("main.close.name", "&cCerrar", Map.of()));
        item.setItemMeta(meta);
        return item;
    }

    private Map<String, String> values(CropGeneratorRecord generator, int growthLevel) {
        int intervalSeconds = (int) Math.max(1, generators.energyIntervalSeconds());
        return Map.ofEntries(
                Map.entry("%level%", Integer.toString(generator.level())),
                Map.entry("%max_level%", Integer.toString(generators.maximumLevel())),
                Map.entry("%next_level%", Integer.toString(generator.level() + 1)),
                Map.entry("%cost%", generators.formatEnergy(generators.upgradeCost(generator.level()))),
                Map.entry("%energy_cost%", generators.formatEnergy(generators.energyCost(generator))),
                Map.entry("%interval%", Integer.toString(intervalSeconds)),
                Map.entry("%growth%", generators.formatEnergy(generators.growthBonusPercent(growthLevel))),
                Map.entry("%harvest%", generators.formatEnergy(generators.harvestBonusPercent(generator))));
    }

    private int slot(String key, int fallback) {
        return generators.menuSlot("main.slots." + key, fallback);
    }

    private Material material(String path, String fallback) {
        return generators.menuMaterial(path, fallback);
    }

    private Component text(String path, String fallback, Map<String, String> placeholders) {
        return replace(generators.menuText(path, fallback), placeholders);
    }

    private Component text(String raw, Map<String, String> placeholders) {
        return replace(raw, placeholders);
    }

    private Component replace(String value, Map<String, String> placeholders) {
        String result = value;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            result = result.replace(placeholder.getKey(), placeholder.getValue());
        }
        return messages.parseComponent(result);
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
}
