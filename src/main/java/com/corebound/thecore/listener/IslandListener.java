package com.corebound.thecore.listener;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.message.Messages;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public final class IslandListener implements Listener {
    private final TheCorePlugin plugin;
    private final IslandService islandService;
    private final Messages messages;
    private final Set<UUID> joinFallProtection = new HashSet<>();

    public IslandListener(
            TheCorePlugin plugin,
            IslandService islandService,
            Messages messages) {
        this.plugin = plugin;
        this.islandService = islandService;
        this.messages = messages;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        islandService.islandAt(event.getBlock().getLocation()).ifPresent(island -> {
            boolean mayBreak = island.owner().equals(event.getPlayer().getUniqueId())
                    || (plugin.getConfig().getBoolean("protection.allow-trusted-break", true)
                            && island.trustedMembers().contains(event.getPlayer().getUniqueId()));
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        islandService.updateBorder(player);
        if (!plugin.getConfig().getBoolean("protection.prevent-fall-damage-on-join", true)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        joinFallProtection.add(playerId);
        player.setFallDistance(0);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> joinFallProtection.remove(playerId), 100L);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        islandService.updateBorder(event.getPlayer());
        if (event.getPlayer().isOnGround()) {
            joinFallProtection.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        islandService.updateBorder(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && event.getCause() == EntityDamageEvent.DamageCause.FALL
                && joinFallProtection.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void protectBlock(Player player, Location location, java.util.function.Consumer<Boolean> cancel) {
        islandService.islandAt(location).ifPresent(island -> {
            boolean mayBuild = island.canBuild(player.getUniqueId());
            if (!mayBuild) {
                cancel.accept(true);
                messages.send(player, "island-protected");
            }
        });
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }
}
