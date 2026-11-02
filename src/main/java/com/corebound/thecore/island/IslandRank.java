package com.corebound.thecore.island;

import java.util.Locale;

public enum IslandRank {
    MEMBER(0, "rank-member"),
    MODERATOR(1, "rank-moderator"),
    ADMINISTRATOR(2, "rank-administrator"),
    OWNER(3, "rank-owner");

    private final int level;
    private final String messageKey;

    IslandRank(int level, String messageKey) {
        this.level = level;
        this.messageKey = messageKey;
    }

    public int level() {
        return level;
    }

    public String messageKey() {
        return messageKey;
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static IslandRank fromConfig(String value, IslandRank fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }
}
