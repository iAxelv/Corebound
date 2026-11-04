package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.List;
import java.util.Map;
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

    public static ItemStack create(CellRecord cell, int baseIntervalTicks, Messages messages) {
        ItemStack item = new ItemStack(cell.type().itemMaterial());
        ItemMeta meta = item.getItemMeta();
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        String itemName = messages.legacy(cell.type().isRune()
                ? "cell-item.name-rune"
                : "cell-item.name-cell");
        meta.displayName(legacy.deserialize(
                cell.type().color() + itemName + cell.type().displayName(messages) + " T" + cell.tier()));
        meta.lore(cellLore(cell, baseIntervalTicks, messages).stream().map(legacy::deserialize).toList());
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(CELL_TYPE_KEY, PersistentDataType.STRING, cell.type().name());
        meta.getPersistentDataContainer().set(CELL_TIER_KEY, PersistentDataType.INTEGER, cell.tier());
        item.setItemMeta(meta);
        return item;
    }

    public static List<String> cellLore(CellRecord cell, int baseIntervalTicks, Messages messages) {
        String improvement = switch (cell.type()) {
            case FIRE -> messages.formatText("cell-item.improvement.fire", Map.of(
                    "reduction", formatSeconds(cell.fireIntervalReductionTicks() / 20.0),
                    "base", formatSeconds(baseIntervalTicks / 20.0)));
            case LIGHTNING -> messages.formatText("cell-item.improvement.lightning", Map.of(
                    "bonus", formatNumber(cell.lightningBonusPerLink() * 100)));
            case VOID -> messages.formatText("cell-item.improvement.void", Map.of(
                    "chance", formatNumber(cell.voidCriticalChance() * 100)));
            case CONDENSER_EFFICIENCY -> messages.formatText("cell-item.improvement.condenser-efficiency", Map.of(
                    "bonus", formatNumber(cell.condenserEnergyReduction() * 100)));
            case CONDENSER_YIELD -> messages.formatText("cell-item.improvement.condenser-yield", Map.of(
                    "bonus", formatNumber(cell.condenserOreChanceBonus() * 100)));
            case CROP_EFFICIENCY -> messages.formatText("cell-item.improvement.crop-efficiency", Map.of(
                    "bonus", formatNumber(cell.cropHarvestBonus() * 100)));
            case CROP_ENERGY_EFFICIENCY -> messages.formatText("cell-item.improvement.crop-energy-efficiency", Map.of(
                    "bonus", formatNumber(cell.cropEnergyReduction() * 100)));
        };
        String installKey = cell.type().isCropGeneratorRune()
                ? "cell-item.install-crop-generator"
                : cell.type().isCondenserRune()
                        ? "cell-item.install-condenser"
                        : "cell-item.install-pylon";
        return List.of(
                messages.legacy(installKey),
                messages.legacy(cell.type().isRune() ? "cell-item.slot-rune" : "cell-item.slot-cell"),
                improvement,
                messages.legacy("cell-item.fusion-lore"));
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
