package com.corebound.thecore;

import com.corebound.thecore.command.CoreCommand;
import com.corebound.thecore.gui.IslandMenuManager;
import com.corebound.thecore.incremental.CorePlaceListener;
import com.corebound.thecore.incremental.CoreRepository;
import com.corebound.thecore.incremental.CoreService;
import com.corebound.thecore.incremental.CoreMenu;
import com.corebound.thecore.incremental.PylonRepository;
import com.corebound.thecore.incremental.PylonService;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.island.IslandSounds;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.listener.IslandListener;
import com.corebound.thecore.message.Messages;
import com.corebound.thecore.world.VoidChunkGenerator;
import java.io.File;
import java.io.IOException;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

public final class TheCorePlugin extends JavaPlugin {
    private IslandRepository islandRepository;
    private IslandService islandService;
    private CoreService coreService;
    private PylonService pylonService;
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("lang/messages_es.yml");
        saveResourceIfMissing("is/is_settings.yml");
        saveResourceIfMissing("is/is_members.yml");
        saveResourceIfMissing("is/is_perms.yml");
        saveResourceIfMissing("core/core.yml");
        saveResourceIfMissing("core/core_menu.yml");
        saveResourceIfMissing("core/core_upgrades.yml");
        saveResourceIfMissing("core/pylons.yml");

        try {
            File schematicsFolder = new File(getDataFolder(), "schematics");
            if (!schematicsFolder.isDirectory() && !schematicsFolder.mkdirs()) {
                throw new IOException("Could not create " + schematicsFolder);
            }
            validateIslandSpacing();
        } catch (IOException exception) {
            getLogger().severe("Could not initialize plugin data: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        messages = new Messages(
                new File(getDataFolder(), "lang/messages_es.yml"), getResource("lang/messages_es.yml"));
        islandRepository = new IslandRepository(new File(getDataFolder(), "data/islands.yml"), this);

        String worldName = getConfig().getString("world.name", "core_void_world");
        WorldCreator creator = new WorldCreator(worldName);
        creator.generator(new VoidChunkGenerator());
        creator.generateStructures(false);
        World islandWorld = creator.createWorld();
        if (islandWorld == null) {
            getLogger().severe("Paper could not create or load island world '" + worldName + "'.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        IslandSounds sounds = new IslandSounds(this);
        islandService = new IslandService(this, islandWorld, islandRepository, messages, sounds);
        try {
            CoreRepository coreRepository = new CoreRepository(new File(getDataFolder(), "data/cores.yml"), this);
            coreService = new CoreService(
                    this,
                    islandService,
                    coreRepository,
                    messages,
                    new File(getDataFolder(), "core/core.yml"));
            pylonService = new PylonService(
                    this,
                    islandService,
                    coreService,
                    coreRepository,
                    new PylonRepository(new File(getDataFolder(), "data/pylons.yml"), this),
                    messages,
                    new File(getDataFolder(), "core/pylons.yml"));
            coreService.attachPylonService(pylonService);
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Could not initialize the core modules: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        IslandMenuManager menus = new IslandMenuManager(this, islandRepository, islandService, messages, sounds);
        CoreCommand coreCommand = new CoreCommand(this, islandService, islandRepository, messages, menus);
        if (getCommand("is") == null) {
            getLogger().severe("The /is command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("is").setExecutor(coreCommand);
        getCommand("is").setTabCompleter(coreCommand);
        getServer().getPluginManager().registerEvents(
                new IslandListener(this, islandService, messages), this);
        getServer().getPluginManager().registerEvents(
                new CorePlaceListener(this, coreService, pylonService), this);
        getServer().getPluginManager().registerEvents(pylonService.menuListener(), this);
        getServer().getPluginManager().registerEvents(coreService.menuListener(), this);
        getServer().getPluginManager().registerEvents(coreService.upgradesMenuListener(), this);
        getServer().getPluginManager().registerEvents(menus, this);
        coreService.initializeLoadedEntities();
        pylonService.initializeLoadedEntities();
        pylonService.initializeRangeGuides();
        getLogger().info("Corebound island foundation enabled.");
    }

    @Override
    public void onDisable() {
        if (islandRepository != null) {
            try {
                islandRepository.flush();
            } catch (IOException exception) {
                getLogger().severe("Could not save island data: " + exception.getMessage());
            }
        }
        if (coreService != null) {
            try {
                coreService.flush();
            } catch (IOException exception) {
                getLogger().severe("Could not save core data: " + exception.getMessage());
            }
            if (pylonService != null) {
                try {
                    pylonService.flush();
                } catch (IOException exception) {
                    getLogger().severe("Could not save pylon data: " + exception.getMessage());
                }
            }
        }
    }

    private void validateIslandSpacing() throws IOException {
        int spacing = getConfig().getInt("world.distance-between-islands");
        double radius = getConfig().getDouble("world.island-radius");
        if (spacing <= 0 || radius <= 0 || spacing <= radius * 2) {
            throw new IOException(
                    "world.distance-between-islands must be greater than twice world.island-radius.");
        }
    }

    private void saveResourceIfMissing(String resourcePath) {
        File destination = new File(getDataFolder(), resourcePath.replace('/', File.separatorChar));
        if (!destination.isFile()) {
            saveResource(resourcePath, false);
        }
    }
}
