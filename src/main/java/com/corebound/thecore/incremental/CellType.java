package com.corebound.thecore.incremental;

import org.bukkit.Material;
import org.bukkit.Particle;

public enum CellType {
    FIRE("Ígnea", Material.BLAZE_POWDER, "&6", Material.MAGMA_BLOCK, Particle.FLAME),
    LIGHTNING("Sobrecarga", Material.COPPER_INGOT, "&e", Material.SEA_LANTERN, Particle.ELECTRIC_SPARK),
    VOID("Vacío", Material.ENDER_PEARL, "&5", Material.CRYING_OBSIDIAN, Particle.PORTAL);

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
}
