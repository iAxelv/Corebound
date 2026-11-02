package com.corebound.thecore.island;

public enum IslandPermission {
    INVITE("invite"),
    DELETE("delete"),
    RENAME("rename"),
    KICK_USERS("kick-users"),
    OPEN_CLOSE("open-close"),
    PROMOTE("promote"),
    SET_SPAWN("set-spawn"),
    MANAGE_PERMS("manage-perms"),
    SETTINGS("settings");

    private final String configKey;

    IslandPermission(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    public static IslandPermission fromConfigKey(String configKey) {
        for (IslandPermission permission : values()) {
            if (permission.configKey.equals(configKey)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unknown island permission: " + configKey);
    }
}
