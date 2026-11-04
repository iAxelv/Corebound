package com.corebound.thecore.incremental;

import org.bukkit.Material;
import org.bukkit.Particle;

public enum CellType {
    FIRE("Ígnea", Material.BLAZE_POWDER, "&6", Material.MAGMA_BLOCK, Particle.FLAME),
    LIGHTNING("Sobrecarga", Material.COPPER_INGOT, "&e", Material.SEA_LANTERN, Particle.ELECTRIC_SPARK),
    VOID("Vacío", Material.ENDER_PEARL, "&5", Material.CRYING_OBSIDIAN, Particle.PORTAL),
    CONDENSER_EFFICIENCY(
            "Eficiencia material", Material.QUARTZ, "&b", Material.BLAST_FURNACE, Particle.CRIT),
    CONDENSER_YIELD(
            "Prospección material", Material.PRISMARINE_CRYSTALS, "&a", Material.BLAST_FURNACE, Particle.HAPPY_VILLAGER),
    CROP_EFFICIENCY(
            "Cosecha fértil", Material.GOLDEN_CARROT, "&a", Material.COMPOSTER, Particle.HAPPY_VILLAGER),
    CROP_ENERGY_EFFICIENCY(
            "Eficiencia de cultivo", Material.WHEAT_SEEDS, "&2", Material.COMPOSTER, Particle.COMPOSTER);

    private static final java.util.Set<CellType> CONDENSER_TYPES =
            java.util.Set.of(CONDENSER_EFFICIENCY, CONDENSER_YIELD);
    private static final java.util.Set<CellType> CROP_GENERATOR_TYPES =
            java.util.Set.of(CROP_EFFICIENCY, CROP_ENERGY_EFFICIENCY);

    private final String displayName;
    private final Material itemMaterial;
    private final String color;
    private final Material baseMaterial;
    private final Particle particle;

    CellType(String displayName, Material itemMaterial, String color, Material baseMaterial, Particle particle) {
        this.displayName = displayName;
        this.itemMaterial = itemMaterial;
        this.color = color;
        this.baseMaterial = baseMaterial;
        this.particle = particle;
    }

    public String displayName() {
        return displayName;
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

    public boolean isRune() {
        return isCondenserRune() || isCropGeneratorRune();
    }

    public boolean isPylonCell() {
        return !isRune();
    }
}
