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
        String itemName = cell.type().isRune() ? "Runa " : "Célula ";
        meta.displayName(legacy.deserialize(
                cell.type().color() + itemName + cell.type().displayName() + " T" + cell.tier()));
        meta.lore(cellLore(cell, baseIntervalTicks).stream().map(legacy::deserialize).toList());
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(CELL_TYPE_KEY, PersistentDataType.STRING, cell.type().name());
        meta.getPersistentDataContainer().set(CELL_TIER_KEY, PersistentDataType.INTEGER, cell.tier());
        item.setItemMeta(meta);
        return item;
    }

    public static List<String> cellLore(CellRecord cell, int baseIntervalTicks) {
        String improvement = switch (cell.type()) {
            case FIRE -> "&6Mejora: &e-" + formatSeconds(cell.fireIntervalReductionTicks() / 20.0)
                    + "s entre pulsos"
                    + " &7(mínimo 1s; base " + formatSeconds(baseIntervalTicks / 20.0) + "s).";
            case LIGHTNING -> "&6Mejora: &e+" + formatNumber(cell.lightningBonusPerLink() * 100)
                    + "% de producción por enlace.";
            case VOID -> "&6Mejora: &e" + formatNumber(cell.voidCriticalChance() * 100)
                    + "% de crítico &7(x3.5 energía).";
            case CONDENSER_EFFICIENCY -> "&6Mejora: &e-"
                    + formatNumber(cell.condenserEnergyReduction() * 100) + "% de consumo del condensador.";
            case CONDENSER_YIELD -> "&6Mejora: &e+"
                    + formatNumber(cell.condenserOreChanceBonus() * 100) + "% de probabilidad de mineral.";
            case CROP_EFFICIENCY -> "&6Mejora: &e+"
                    + formatNumber(cell.cropHarvestBonus() * 100) + "% de objetos al cosechar.";
            case CROP_ENERGY_EFFICIENCY -> "&6Mejora: &e-"
                    + formatNumber(cell.cropEnergyReduction() * 100) + "% de consumo del generador.";
        };
        return List.of(
                cell.type().isCropGeneratorRune()
                        ? "&7Instalación: &fMayús+clic en la GUI de runas del Generador"
                        : cell.type().isCondenserRune()
                                ? "&7Instalación: &fMayús+clic en la GUI de runas del Condensador"
                                : "&7Instalación: &fMayús+clic en la GUI del Pilón",
                cell.type().isRune() ? "&7Ranura: &fRuna" : "&7Ranura: &fCélula elemental",
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
