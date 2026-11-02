package com.corebound.thecore.island;

import org.bukkit.Location;
import org.bukkit.World;

public record IslandSpawn(double x, double y, double z, float yaw, float pitch) {
    public static IslandSpawn from(Location location) {
        return new IslandSpawn(
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
