package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import org.bukkit.Material;
import org.bukkit.Particle;

public enum CellType {
    FIRE(Material.BLAZE_POWDER, "&6", Material.MAGMA_BLOCK, Particle.FLAME),
    LIGHTNING(Material.COPPER_INGOT, "&e", Material.SEA_LANTERN, Particle.ELECTRIC_SPARK),
    VOID(Material.ENDER_PEARL, "&5", Material.CRYING_OBSIDIAN, Particle.PORTAL),
    ENERGY(Material.AMETHYST_SHARD, "&d", Material.AMETHYST_BLOCK, Particle.END_ROD),
    CONDENSER_EFFICIENCY(
            Material.QUARTZ, "&b", Material.BLAST_FURNACE, Particle.CRIT),
    CONDENSER_YIELD(
            Material.PRISMARINE_CRYSTALS, "&a", Material.BLAST_FURNACE, Particle.HAPPY_VILLAGER),
    CROP_EFFICIENCY(
            Material.GOLDEN_CARROT, "&a", Material.COMPOSTER, Particle.HAPPY_VILLAGER),
    CROP_ENERGY_EFFICIENCY(
            Material.WHEAT_SEEDS, "&2", Material.COMPOSTER, Particle.COMPOSTER),
    BATTERY_CAPACITY(
            Material.COPPER_NUGGET, "&b", Material.COPPER_BLOCK, Particle.ELECTRIC_SPARK),
    BATTERY_OFFLINE(
            Material.ECHO_SHARD, "&d", Material.COPPER_BLOCK, Particle.END_ROD);

    private static final java.util.Set<CellType> CONDENSER_TYPES =
            java.util.Set.of(CONDENSER_EFFICIENCY, CONDENSER_YIELD);
    private static final java.util.Set<CellType> CROP_GENERATOR_TYPES =
            java.util.Set.of(CROP_EFFICIENCY, CROP_ENERGY_EFFICIENCY);
    private static final java.util.Set<CellType> BATTERY_TYPES =
            java.util.Set.of(BATTERY_CAPACITY, BATTERY_OFFLINE);

    private final Material itemMaterial;
    private final String color;
    private final Material baseMaterial;
    private final Particle particle;

    CellType(Material itemMaterial, String color, Material baseMaterial, Particle particle) {
        this.itemMaterial = itemMaterial;
        this.color = color;
        this.baseMaterial = baseMaterial;
        this.particle = particle;
    }

    public String displayName(Messages messages) {
        return messages.text("cell-types." + configKey());
    }

    public String configKey() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public Material itemMaterial() {
        return itemMaterial;
    }

    public String color() {
        return color;
    }

    public Material baseMaterial() {
        return baseMaterial;
    }

    public Particle particle() {
        return particle;
    }

    public boolean isCondenserRune() {
        return CONDENSER_TYPES.contains(this);
    }

    public boolean isCropGeneratorRune() {
        return CROP_GENERATOR_TYPES.contains(this);
    }

    public boolean isBatteryRune() {
        return BATTERY_TYPES.contains(this);
    }

    public boolean isRune() {
        return isCondenserRune() || isCropGeneratorRune() || isBatteryRune();
    }

    public boolean isPylonCell() {
        return !isRune();
    }
}
