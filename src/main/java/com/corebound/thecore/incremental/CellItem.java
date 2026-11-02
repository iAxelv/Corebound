package com.corebound.thecore.incremental;

import java.util.List;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class CellItem {
    private static final NamespacedKey CELL_TYPE_KEY = new NamespacedKey("thecore", "cell_type");
    private static final NamespacedKey CELL_TIER_KEY = new NamespacedKey("thecore", "cell_tier");

    private CellItem() {
    }

    public static ItemStack create(CellRecord cell, int baseIntervalTicks) {
        ItemStack item = new ItemStack(cell.type().itemMaterial());
        ItemMeta meta = item.getItemMeta();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        meta.displayName(legacy.deserialize(
                cell.type().color() + "Célula " + cell.type().displayName() + " T" + cell.tier()));
        meta.lore(cellLore(cell, baseIntervalTicks).stream().map(legacy::deserialize).toList());
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(CELL_TYPE_KEY, PersistentDataType.STRING, cell.type().name());
        meta.getPersistentDataContainer().set(CELL_TIER_KEY, PersistentDataType.INTEGER, cell.tier());
        item.setItemMeta(meta);
        return item;
    }

    public static List<String> cellLore(CellRecord cell, int baseIntervalTicks) {
        int tierMultiplier = 1 << (cell.tier() - 1);
        String improvement = switch (cell.type()) {
            case FIRE -> "&6Mejora: &e-" + formatSeconds(tierMultiplier) + "s entre pulsos"
                    + " &7(mínimo 1s; base " + formatSeconds(baseIntervalTicks / 20.0) + "s).";
            case LIGHTNING -> "&6Mejora: &e+" + formatNumber(25 * tierMultiplier)
                    + "% de producción por enlace.";
            case VOID -> "&6Mejora: &e" + formatNumber(Math.min(100, 15 * tierMultiplier))
                    + "% de crítico &7(x3.5 energía).";
        };
        return List.of(
                "&7Instalación: &fClic derecho al Pilón",
                "&7Ranura: &fCélula elemental",
                improvement,
                "&8Fusiona dos iguales para subir de tier.");
    }

    private static String formatSeconds(double seconds) {
        return formatNumber(seconds);
    }

    private static String formatNumber(double number) {
        return String.format(java.util.Locale.ROOT, "%.1f", number);
    }

    public static CellRecord read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String typeName = item.getItemMeta().getPersistentDataContainer()
                .get(CELL_TYPE_KEY, PersistentDataType.STRING);
        Integer tier = item.getItemMeta().getPersistentDataContainer()
                .get(CELL_TIER_KEY, PersistentDataType.INTEGER);
        if (typeName == null || tier == null) {
            return null;
        }
        try {
            return new CellRecord(CellType.valueOf(typeName), tier);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static boolean isCell(ItemStack item) {
        return read(item) != null;
    }
}
