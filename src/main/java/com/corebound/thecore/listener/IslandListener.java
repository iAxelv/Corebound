package com.corebound.thecore.listener;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandSettings;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;

public final class IslandListener implements Listener {
    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final Messages messages;
    private final Set<UUID> joinFallProtection = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> nextStormStrike = new ConcurrentHashMap<>();
    private final Map<UUID, EnvironmentSnapshot> playerEnvironments = new ConcurrentHashMap<>();
    private long environmentTick;

    private record EnvironmentSnapshot(UUID islandOwner, IslandSettings settings) {
    }

    public IslandListener(
            TheCorePlugin plugin,
            IslandService islandService,
            Messages messages) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.messages = messages;
        plugin.getServer().getGlobalRegionScheduler()
                .runAtFixedRate(plugin, task -> applyStormEffects(), 20L, 20L);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        islandService.islandAt(event.getBlock().getLocation()).ifPresent(island -> {
            boolean mayBreak = hasBuildBypass(event.getPlayer())
                    || island.owner().equals(event.getPlayer().getUniqueId())
                    || island.members().containsKey(event.getPlayer().getUniqueId())
                    || (plugin.getConfig().getBoolean("protection.allow-trusted-break", true)
                            && island.coops().contains(event.getPlayer().getUniqueId()));
            if (!mayBreak) {
                event.setCancelled(true);
                messages.send(event.getPlayer(), "island-protected");
            }
        });
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        protectBlock(event.getPlayer(), event.getBlock().getLocation(), event::setCancelled);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getConfig().getBoolean("protection.allow-visitor-interact", false)
                || event.getClickedBlock() == null) {
            return;
        }
        protectBlock(event.getPlayer(), event.getClickedBlock().getLocation(), event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protectBlock(
                event.getPlayer(),
                event.getBlockClicked().getRelative(event.getBlockFace()).getLocation(),
                event::setCancelled);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protectBlock(event.getPlayer(), event.getBlockClicked().getLocation(), event::setCancelled);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                islandService.refreshBorder(player);
                refreshPlayerEnvironment(player);
                islandService.islandAt(player.getLocation())
                        .filter(island -> islandService.isBannedFromIsland(player.getUniqueId(), island))
                        .ifPresent(island -> islandService.ejectFromIsland(player, island, "island-banned"));
            }
        }, null);
        if (!plugin.getConfig().getBoolean("protection.prevent-fall-damage-on-join", true)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        joinFallProtection.add(playerId);
        player.setFallDistance(0);
        player.getScheduler().runDelayed(
                plugin, task -> joinFallProtection.remove(playerId), null, 100L);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        IslandData island = islandService.islandAt(event.getTo()).orElse(null);
        if (island != null && islandService.isBannedFromIsland(event.getPlayer().getUniqueId(), island)) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "island-banned");
            return;
        }
        refreshPlayerEnvironment(event.getPlayer(), island);
        islandService.updateBorder(event.getPlayer(), event.getTo());
        if (event.getPlayer().isOnGround()) {
            joinFallProtection.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        islandService.updateBorder(player);
        refreshPlayerEnvironment(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportEnvironment(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                islandService.updateBorder(player);
                refreshPlayerEnvironment(player);
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIslandTeleport(PlayerTeleportEvent event) {
        islandService.islandAt(event.getTo())
                .filter(island -> islandService.isBannedFromIsland(event.getPlayer().getUniqueId(), island))
                .ifPresent(island -> {
                    event.setCancelled(true);
                    messages.send(event.getPlayer(), "island-banned");
                });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        islandService.islandAt(event.getRespawnLocation())
                .filter(island -> islandService.isBannedFromIsland(event.getPlayer().getUniqueId(), island))
                .ifPresent(island -> {
                    org.bukkit.World spawnWorld = plugin.getServer().getWorlds().stream()
                            .filter(world -> !world.getUID().equals(islandService.islandWorld().getUID()))
                            .findFirst()
                            .orElse(islandService.islandWorld());
                    event.setRespawnLocation(spawnWorld.getSpawnLocation());
                    messages.send(event.getPlayer(), "island-banned");
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        islandService.clearBypass(event.getPlayer().getUniqueId());
        islandService.clearIslandChat(event.getPlayer().getUniqueId());
        playerEnvironments.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIslandChat(AsyncChatEvent event) {
        UUID senderId = event.getPlayer().getUniqueId();
        if (!islandService.isIslandChatEnabled(senderId)) {
            return;
        }
        event.setCancelled(true);
        event.viewers().clear();
        Component message = event.message();
        event.getPlayer().getScheduler().run(
                plugin, task -> islandService.sendIslandChat(senderId, message), null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            islandService.islandAt(player.getLocation())
                    .filter(island -> !island.settings().damage())
                    .ifPresent(island -> event.setCancelled(true));
        }
        if (event.getEntity() instanceof Player player
                && event.getCause() == EntityDamageEvent.DamageCause.FALL
                && joinFallProtection.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIslandPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) {
            attacker = direct;
        } else if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player shooterPlayer) {
                attacker = shooterPlayer;
            }
        }
        if (attacker == null) {
            return;
        }
        islandService.islandAt(victim.getLocation())
                .filter(island -> !island.settings().pvp())
                .ifPresent(island -> event.setCancelled(true));
    }

    @EventHandler(ignoreCancelled = true)
    public void onIslandExplosion(EntityExplodeEvent event) {
        IslandData sourceIsland = islandService.islandAt(event.getLocation()).orElse(null);
        if (sourceIsland != null && !sourceIsland.settings().explosions()) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block -> islandService.islandAt(block.getLocation())
                .filter(island -> !island.settings().explosions())
                .isPresent());
    }

    @EventHandler(ignoreCancelled = true)
    public void onIslandBlockExplosion(BlockExplodeEvent event) {
        IslandData sourceIsland = islandService.islandAt(event.getBlock().getLocation()).orElse(null);
        if (sourceIsland != null && !sourceIsland.settings().explosions()) {
            event.setCancelled(true);
            return;
        }
        event.blockList().removeIf(block -> islandService.islandAt(block.getLocation())
                .filter(island -> !island.settings().explosions())
                .isPresent());
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        IslandData sourceIsland = islandService.islandAt(event.getBlock().getLocation()).orElse(null);
        IslandData targetIsland = islandService.islandAt(event.getToBlock().getLocation()).orElse(null);
        boolean isLava = event.getBlock().getType() == org.bukkit.Material.LAVA;
        if (sourceIsland != null && isFlowDisabled(sourceIsland.settings(), isLava)
                || targetIsland != null && isFlowDisabled(targetIsland.settings(), isLava)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        islandService.islandAt(event.getBlock().getLocation())
                .filter(island -> !island.settings().redstone())
                .ifPresent(island -> event.setNewCurrent(event.getOldCurrent()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        islandService.islandAt(event.getLocation()).ifPresent(island -> {
            IslandSettings settings = island.settings();
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER && !settings.spawners()
                    || event.getEntity() instanceof Monster && !settings.hostileMobs()
                    || !(event.getEntity() instanceof Monster) && !settings.passiveMobs()) {
                event.setCancelled(true);
            }
        });
    }

    private void protectBlock(Player player, Location location, java.util.function.Consumer<Boolean> cancel) {
        islandService.islandAt(location).ifPresent(island -> {
            boolean mayBuild = hasBuildBypass(player) || island.canBuild(player.getUniqueId());
            if (!mayBuild) {
                cancel.accept(true);
                messages.send(player, "island-protected");
            }
        });
    }

    private boolean hasBuildBypass(Player player) {
        return player.hasPermission("thecore.build") || player.hasPermission("thecore.*");
    }

    private boolean isFlowDisabled(IslandSettings settings, boolean lava) {
        return lava ? !settings.lavaFlow() : !settings.waterFlow();
    }

    private void refreshPlayerEnvironment(Player player) {
        refreshPlayerEnvironment(player, islandService.islandAt(player.getLocation()).orElse(null));
    }

    private void refreshPlayerEnvironment(Player player, IslandData island) {
        EnvironmentSnapshot current = island == null
                ? null
                : new EnvironmentSnapshot(island.owner(), island.settings());
        UUID playerId = player.getUniqueId();
        if (java.util.Objects.equals(playerEnvironments.get(playerId), current)) {
            return;
        }
        if (current == null) {
            playerEnvironments.remove(playerId);
            islandService.applyPersonalEnvironment(player, null);
            return;
        }
        playerEnvironments.put(playerId, current);
        islandService.applyPersonalEnvironment(player, current.settings());
    }

    private void applyStormEffects() {
        environmentTick += 20;
        for (IslandData island : islandService.allIslands()) {
            if (!island.settings().alwaysThunderstorm()) {
                nextStormStrike.remove(island.owner());
                continue;
            }
            long nextStrike = nextStormStrike.getOrDefault(island.owner(), environmentTick);
            if (environmentTick < nextStrike) {
                continue;
            }
            int radius = (int) plugin.getConfig().getDouble("world.island-radius", 35);
            int x = Math.toIntExact(island.centerX())
                    + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            int z = Math.toIntExact(island.centerZ())
                    + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            plugin.getServer().getRegionScheduler().run(
                    plugin, islandService.islandWorld(), x >> 4, z >> 4, task -> {
                        int y = Math.min(islandService.islandWorld().getMaxHeight() - 1,
                                islandService.islandWorld().getHighestBlockYAt(x, z) + 1);
                        islandService.islandWorld().strikeLightningEffect(
                                new Location(islandService.islandWorld(), x, y, z));
                    });
            nextStormStrike.put(island.owner(),
                    environmentTick + ThreadLocalRandom.current().nextLong(120L, 241L));
        }
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }
}
