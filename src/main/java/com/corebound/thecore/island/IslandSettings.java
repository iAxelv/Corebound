package com.corebound.thecore.island;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;

public record IslandSettings(
        boolean pvp,
        boolean alwaysDay,
        boolean alwaysNight,
        boolean alwaysMidnight,
        boolean alwaysNoon,
        boolean rainCycle,
        boolean alwaysSunny,
        boolean alwaysRainy,
        boolean thunderCycle,
        boolean alwaysThunderstorm,
        boolean explosions,
        boolean lavaFlow,
        boolean waterFlow,
        boolean redstone,
        boolean passiveMobs,
        boolean hostileMobs,
        boolean spawners,
        boolean damage) {

    public static IslandSettings defaults() {
        return new IslandSettings(
                false, false, false, false, false,
                true, false, false, true, false,
                false, true, true, true, true, true, true, true);
    }

    public IslandSettings toggle(String key) {
        Map<String, Boolean> values = values();
        Boolean current = values.get(key);
        if (current == null) {
            throw new IllegalArgumentException("Unknown island setting: " + key);
        }
        if (key.startsWith("always") && !key.equals("alwaysSunny") && !key.equals("alwaysRainy")
                && !key.equals("alwaysThunderstorm") && !current) {
            values.put("alwaysDay", false);
            values.put("alwaysNight", false);
            values.put("alwaysMidnight", false);
            values.put("alwaysNoon", false);
        }
        if (key.equals("alwaysSunny") && !current) {
            values.put("alwaysRainy", false);
            values.put("rainCycle", false);
            values.put("thunderCycle", false);
            values.put("alwaysThunderstorm", false);
        } else if (key.equals("alwaysRainy") && !current) {
            values.put("alwaysSunny", false);
            values.put("thunderCycle", false);
            values.put("alwaysThunderstorm", false);
        } else if (key.equals("alwaysThunderstorm") && !current) {
            values.put("alwaysSunny", false);
            values.put("alwaysRainy", false);
            values.put("rainCycle", false);
        }
        if (current && (key.equals("alwaysSunny") || key.equals("alwaysRainy")
                || key.equals("alwaysThunderstorm"))) {
            values.put("rainCycle", true);
            values.put("thunderCycle", true);
        }
        if (key.equals("rainCycle") && !current) {
            values.put("alwaysSunny", false);
            values.put("alwaysRainy", false);
            values.put("alwaysThunderstorm", false);
        }
        if (key.equals("thunderCycle") && !current) {
            values.put("alwaysThunderstorm", false);
        }
        values.put(key, !current);
        return from(values);
    }

    public Map<String, Boolean> values() {
        Map<String, Boolean> values = new LinkedHashMap<>();
        values.put("pvp", pvp);
        values.put("alwaysDay", alwaysDay);
        values.put("alwaysNight", alwaysNight);
        values.put("alwaysMidnight", alwaysMidnight);
        values.put("alwaysNoon", alwaysNoon);
        values.put("rainCycle", rainCycle);
        values.put("alwaysSunny", alwaysSunny);
        values.put("alwaysRainy", alwaysRainy);
        values.put("thunderCycle", thunderCycle);
        values.put("alwaysThunderstorm", alwaysThunderstorm);
        values.put("explosions", explosions);
        values.put("lavaFlow", lavaFlow);
        values.put("waterFlow", waterFlow);
        values.put("redstone", redstone);
        values.put("passiveMobs", passiveMobs);
        values.put("hostileMobs", hostileMobs);
        values.put("spawners", spawners);
        values.put("damage", damage);
        return values;
    }

    public void save(ConfigurationSection section) {
        values().forEach(section::set);
    }

    public static IslandSettings load(ConfigurationSection section) {
        Map<String, Boolean> values = defaults().values();
        if (section != null) {
            values.replaceAll((key, value) -> section.getBoolean(key, value));
        }
        return from(values);
    }

    private static IslandSettings from(Map<String, Boolean> values) {
        return new IslandSettings(
                values.get("pvp"),
                values.get("alwaysDay"),
                values.get("alwaysNight"),
                values.get("alwaysMidnight"),
                values.get("alwaysNoon"),
                values.get("rainCycle"),
                values.get("alwaysSunny"),
                values.get("alwaysRainy"),
                values.get("thunderCycle"),
                values.get("alwaysThunderstorm"),
                values.get("explosions"),
                values.get("lavaFlow"),
                values.get("waterFlow"),
                values.get("redstone"),
                values.get("passiveMobs"),
                values.get("hostileMobs"),
                values.get("spawners"),
                values.get("damage"));
    }
}
