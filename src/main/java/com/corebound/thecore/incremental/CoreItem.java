package com.corebound.thecore.incremental;

import com.corebound.thecore.message.Messages;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class CoreItem {
    private CoreItem() {
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level) {
        return create(config, messages, itemKey, level, 0.0, 0, 0L);
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level,
            double energy,
            int hits,
            long lastHitAt) {
        return create(config, messages, itemKey, level, energy, hits, lastHitAt, 0, 0, 0);
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level,
            double energy,
            int hits,
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount) {
        return create(config, messages, itemKey, level, energy, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, prestigeCount);
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level,
            double energy,
            int hits,
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount,
            int coreFragments) {
        return create(config, messages, itemKey, level, energy, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, coreFragments, 0, 0);
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level,
            double energy,
            int hits,
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount,
            int coreFragments,
            int astralResonance,
            int astralPylonAttunement) {
        return create(config, messages, itemKey, level, energy, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, prestigeCount, coreFragments,
                astralResonance, astralPylonAttunement, 0);
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level,
            double energy,
            int hits,
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount,
            int coreFragments,
            int astralResonance,
            int astralPylonAttunement,
            int upgradeCapacity) {
        Material material = Material.matchMaterial(config.getString("item.material", "NETHERITE_BLOCK"));
        if (material == null || !material.isItem() || !material.isBlock()) {
            throw new IllegalArgumentException("core.yml item.material must be a valid block item.");
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.parseComponent(replaceStats(
                config.getString("item.display-name", "&5Corebound Core"),
                level,
                Math.pow(1.25, level - 1))));
        List<String> lore = config.getStringList("item.lore").stream()
                .map(line -> replaceStats(line, level, Math.pow(1.25, level - 1)))
                .toList();
        meta.lore(lore.stream().map(messages::parseComponent).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(levelKey(itemKey), PersistentDataType.INTEGER, level);
        meta.getPersistentDataContainer().set(energyKey(itemKey), PersistentDataType.DOUBLE, energy);
        meta.getPersistentDataContainer().set(hitsKey(itemKey), PersistentDataType.INTEGER, hits);
        meta.getPersistentDataContainer().set(lastHitKey(itemKey), PersistentDataType.LONG, lastHitAt);
        meta.getPersistentDataContainer().set(overchargeKey(itemKey), PersistentDataType.INTEGER, upgradeOvercharge);
        meta.getPersistentDataContainer().set(windowKey(itemKey), PersistentDataType.INTEGER, upgradeWindow);
        meta.getPersistentDataContainer().set(criticalKey(itemKey), PersistentDataType.INTEGER, upgradeCritical);
        meta.getPersistentDataContainer().set(prestigeKey(itemKey), PersistentDataType.INTEGER, prestigeCount);
        meta.getPersistentDataContainer().set(fragmentsKey(itemKey), PersistentDataType.INTEGER, coreFragments);
        meta.getPersistentDataContainer().set(
                astralResonanceKey(itemKey), PersistentDataType.INTEGER, astralResonance);
        meta.getPersistentDataContainer().set(
                astralPylonAttunementKey(itemKey), PersistentDataType.INTEGER, astralPylonAttunement);
        meta.getPersistentDataContainer().set(
                capacityUpgradeKey(itemKey), PersistentDataType.INTEGER, upgradeCapacity);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack create(
            YamlConfiguration config,
            Messages messages,
            NamespacedKey itemKey,
            int level,
            double energy,
            int hits,
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical) {
        return create(config, messages, itemKey, level, energy, hits, lastHitAt,
                upgradeOvercharge, upgradeWindow, upgradeCritical, 0);
    }

    public static Progress progress(ItemStack item, NamespacedKey itemKey) {
        ItemMeta meta = item.getItemMeta();
        int prestigeCount = meta.getPersistentDataContainer().getOrDefault(
                prestigeKey(itemKey), PersistentDataType.INTEGER, 0);
        return new Progress(
                meta.getPersistentDataContainer().getOrDefault(
                        levelKey(itemKey), PersistentDataType.INTEGER, 1),
                meta.getPersistentDataContainer().getOrDefault(
                        energyKey(itemKey), PersistentDataType.DOUBLE, 0.0),
                meta.getPersistentDataContainer().getOrDefault(
                        hitsKey(itemKey), PersistentDataType.INTEGER, 0),
                meta.getPersistentDataContainer().getOrDefault(
                        lastHitKey(itemKey), PersistentDataType.LONG, 0L),
                meta.getPersistentDataContainer().getOrDefault(
                        overchargeKey(itemKey), PersistentDataType.INTEGER, 0),
                meta.getPersistentDataContainer().getOrDefault(
                        windowKey(itemKey), PersistentDataType.INTEGER, 0),
                meta.getPersistentDataContainer().getOrDefault(
                        criticalKey(itemKey), PersistentDataType.INTEGER, 0),
                prestigeCount,
                meta.getPersistentDataContainer().getOrDefault(
                        fragmentsKey(itemKey), PersistentDataType.INTEGER, prestigeCount),
                meta.getPersistentDataContainer().getOrDefault(
                        astralResonanceKey(itemKey), PersistentDataType.INTEGER, 0),
                meta.getPersistentDataContainer().getOrDefault(
                        astralPylonAttunementKey(itemKey), PersistentDataType.INTEGER, 0),
                meta.getPersistentDataContainer().getOrDefault(
                        capacityUpgradeKey(itemKey), PersistentDataType.INTEGER, 0));
    }

    private static NamespacedKey levelKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_level");
    }

    private static NamespacedKey energyKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_energy");
    }

    private static NamespacedKey hitsKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_hits");
    }

    private static NamespacedKey lastHitKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_last_hit");
    }

    private static NamespacedKey overchargeKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_upgrade_overcharge");
    }

    private static NamespacedKey windowKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_upgrade_window");
    }

    private static NamespacedKey criticalKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_upgrade_critical");
    }

    private static NamespacedKey prestigeKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_prestige_count");
    }

    private static NamespacedKey fragmentsKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_core_fragments");
    }

    private static NamespacedKey astralResonanceKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_astral_resonance");
    }

    private static NamespacedKey astralPylonAttunementKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_astral_pylon_attunement");
    }

    private static NamespacedKey capacityUpgradeKey(NamespacedKey itemKey) {
        return new NamespacedKey(itemKey.getNamespace(), itemKey.getKey() + "_upgrade_capacity");
    }

    private static String replaceStats(String text, int level, double baseEnergy) {
        return text
                .replace("%level%", Integer.toString(level))
                .replace("%base_energy%", String.format(java.util.Locale.ROOT, "%.1f", baseEnergy));
    }

    public static boolean isCoreItem(ItemStack item, NamespacedKey itemKey) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer()
                .get(itemKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    public record Progress(
            int level,
            double energy,
            int hits,
            long lastHitAt,
            int upgradeOvercharge,
            int upgradeWindow,
            int upgradeCritical,
            int prestigeCount,
            int coreFragments,
            int astralResonance,
            int astralPylonAttunement,
            int upgradeCapacity) {
    }
}
