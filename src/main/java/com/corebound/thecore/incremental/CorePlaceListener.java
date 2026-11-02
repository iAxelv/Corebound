package com.corebound.thecore.incremental;

import com.corebound.thecore.TheCorePlugin;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class CorePlaceListener implements Listener {
    private record PylonPlacementAttempt(UUID playerId, UUID worldId, int x, int y, int z) {
    }

    private final TheCorePlugin plugin;
    private final CoreService coreService;
    private final PylonService pylonService;
    private final Set<InteractionAttempt> interactionAttempts = ConcurrentHashMap.newKeySet();
    private final Set<PylonPlacementAttempt> pylonPlacementAttempts = ConcurrentHashMap.newKeySet();

    private record InteractionAttempt(UUID playerId, UUID entityId) {
    }

    public CorePlaceListener(TheCorePlugin plugin, CoreService coreService, PylonService pylonService) {
        this.plugin = plugin;
        this.coreService = coreService;
        this.pylonService = pylonService;
    }

    @EventHandler
    public void onIslandCreated(IslandCreatedEvent event) {
        coreService.onIslandCreated(event);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        coreService.onPlayerJoin(event.getPlayer());
        pylonService.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(entity -> {
            coreService.onEntityLoad(entity);
            pylonService.onEntityLoad(entity);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCoreItemUse(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            if (coreService.isCoreItem(event.getItem()) || pylonService.isPylonItem(event.getItem())) {
                event.setCancelled(true);
            }
            return;
        }
        if (coreService.isCoreItem(event.getItem())) {
            event.setCancelled(true);
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                coreService.placeCore(event.getPlayer(), event.getClickedBlock());
            }
            return;
        }
        if (pylonService.isPylonItem(event.getItem())) {
            event.setCancelled(true);
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                Player player = event.getPlayer();
                org.bukkit.block.Block block = event.getClickedBlock();
                PylonPlacementAttempt attempt = new PylonPlacementAttempt(
                        player.getUniqueId(),
                        block.getWorld().getUID(),
                        block.getX(),
                        block.getY(),
                        block.getZ());
                if (pylonPlacementAttempts.add(attempt)) {
                    player.getScheduler().runDelayed(
                            plugin,
                            task -> pylonPlacementAttempts.remove(attempt),
                            () -> pylonPlacementAttempts.remove(attempt),
                            1L);
                    pylonService.place(player, block);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCoreEntityInteract(PlayerInteractEntityEvent event) {
        handleCoreEntityInteract(event, event.getRightClicked(), event.getHand());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCoreEntityInteractAt(PlayerInteractAtEntityEvent event) {
        handleCoreEntityInteract(event, event.getRightClicked(), event.getHand());
    }

    private void handleCoreEntityInteract(
            PlayerInteractEntityEvent event, org.bukkit.entity.Entity entity, EquipmentSlot hand) {
        if (coreService.isCoreEntity(entity)) {
            event.setCancelled(true);
            if (hand == EquipmentSlot.HAND) {
                Player player = event.getPlayer();
                InteractionAttempt attempt = new InteractionAttempt(player.getUniqueId(), entity.getUniqueId());
                if (!interactionAttempts.add(attempt)) {
                    return;
                }
                player.getScheduler().runDelayed(
                        plugin,
                        task -> interactionAttempts.remove(attempt),
                        () -> interactionAttempts.remove(attempt),
                        1L);
                if (player.isSneaking()) {
                    coreService.pickupCore(player, entity);
                } else {
                    coreService.openCoreMenu(player, entity);
                }
            }
        } else if (pylonService.isPylonEntity(entity)) {
            event.setCancelled(true);
            if (hand == EquipmentSlot.HAND && event.getPlayer().isSneaking()) {
                Player player = event.getPlayer();
                InteractionAttempt attempt = new InteractionAttempt(player.getUniqueId(), entity.getUniqueId());
                if (interactionAttempts.add(attempt)) {
                    player.getScheduler().runDelayed(
                            plugin,
                            task -> interactionAttempts.remove(attempt),
                            () -> interactionAttempts.remove(attempt),
                            1L);
                    pylonService.pickupPylon(player, entity);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCoreHit(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Interaction && coreService.isCoreEntity(event.getEntity())) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Player player) {
                coreService.onCoreHit(player, event.getEntity());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCoreEntityDamage(EntityDamageEvent event) {
        if (coreService.isCoreEntity(event.getEntity()) || pylonService.isPylonEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
