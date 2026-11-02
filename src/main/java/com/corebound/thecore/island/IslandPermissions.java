package com.corebound.thecore.island;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

public final class IslandPermissions {
    private final Map<IslandPermission, IslandRank> defaults;

    private IslandPermissions(Map<IslandPermission, IslandRank> defaults) {
        this.defaults = Map.copyOf(defaults);
    }

    public static IslandPermissions load(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        Map<IslandPermission, IslandRank> defaults = defaults();
        for (IslandPermission permission : IslandPermission.values()) {
            IslandRank configured = IslandRank.fromConfig(
                    config.getString("defaults." + permission.configKey()), defaults.get(permission));
            defaults.put(permission,
                    configured == IslandRank.OWNER ? defaults.get(permission) : configured);
        }
        return new IslandPermissions(defaults);
    }

    public static Map<IslandPermission, IslandRank> defaults() {
        Map<IslandPermission, IslandRank> defaults = new EnumMap<>(IslandPermission.class);
        defaults.put(IslandPermission.INVITE, IslandRank.MODERATOR);
        defaults.put(IslandPermission.DELETE, IslandRank.ADMINISTRATOR);
        defaults.put(IslandPermission.RENAME, IslandRank.MODERATOR);
        defaults.put(IslandPermission.KICK_USERS, IslandRank.MODERATOR);
        defaults.put(IslandPermission.OPEN_CLOSE, IslandRank.MEMBER);
        defaults.put(IslandPermission.PROMOTE, IslandRank.ADMINISTRATOR);
        defaults.put(IslandPermission.SET_SPAWN, IslandRank.MODERATOR);
        defaults.put(IslandPermission.MANAGE_PERMS, IslandRank.ADMINISTRATOR);
        defaults.put(IslandPermission.SETTINGS, IslandRank.ADMINISTRATOR);
        return defaults;
    }

    public Map<IslandPermission, IslandRank> defaultsForIsland() {
        return defaults;
    }
}
