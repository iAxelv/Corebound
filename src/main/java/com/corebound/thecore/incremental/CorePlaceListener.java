package com.corebound.thecore.incremental;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;

public final class CorePlaceListener implements Listener {
    private final CoreService coreService;

    public CorePlaceListener(CoreService coreService) {
        this.coreService = coreService;
    }

    @EventHandler
    public void onIslandCreated(IslandCreatedEvent event) {
        coreService.onIslandCreated(event);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        coreService.onPlayerJoin(event.getPlayer());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(coreService::onEntityLoad);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCoreItemUse(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            if (coreService.isCoreItem(event.getItem())) {
                event.setCancelled(true);
            }
            return;
        }
        if (!coreService.isCoreItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        coreService.placeCore(event.getPlayer(), event.getClickedBlock());
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
            if (hand == EquipmentSlot.HAND && event.getPlayer().isSneaking()) {
                coreService.pickupCore(event.getPlayer(), entity);
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
        if (coreService.isCoreEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
