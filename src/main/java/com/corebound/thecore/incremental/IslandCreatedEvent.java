package com.corebound.thecore.incremental;

import com.corebound.thecore.island.IslandData;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class IslandCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final IslandData island;

    public IslandCreatedEvent(IslandData island) {
        this.island = island;
    }

    public IslandData island() {
        return island;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
