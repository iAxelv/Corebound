package com.corebound.thecore;

import com.corebound.thecore.command.CoreCommand;
import com.corebound.thecore.gui.IslandMenuManager;
import com.corebound.thecore.gui.IslandTopMenu;
import com.corebound.thecore.incremental.CorePlaceListener;
import com.corebound.thecore.incremental.CoreRepository;
import com.corebound.thecore.incremental.CoreService;
import com.corebound.thecore.incremental.CondenserRepository;
import com.corebound.thecore.incremental.CondenserService;
import com.corebound.thecore.incremental.CropGeneratorRepository;
import com.corebound.thecore.incremental.CropGeneratorService;
import com.corebound.thecore.incremental.PylonRepository;
import com.corebound.thecore.incremental.PylonService;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.island.IslandSounds;
import com.corebound.thecore.island.IslandService;
import com.corebound.thecore.listener.IslandListener;
import com.corebound.thecore.message.Messages;
import com.corebound.thecore.placeholder.CoreboundPlaceholderExpansion;
import com.corebound.thecore.world.VoidChunkGenerator;
import java.io.File;
import java.io.IOException;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class TheCorePlugin extends JavaPlugin {
    private IslandRepository islandRepository;
    private CoreRepository coreRepository;
    private IslandService islandService;
    private CoreService coreService;
    private PylonService pylonService;
    private CondenserService condenserService;
    private CropGeneratorService cropGeneratorService;
    private Messages messages;
    private IslandSounds sounds;
    private IslandMenuManager menus;
    private IslandTopMenu topMenu;
    private CoreboundPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("lang/messages_es.yml");
        saveResourceIfMissing("is/is_settings.yml");
        saveResourceIfMissing("is/is_members.yml");
        saveResourceIfMissing("is/is_perms.yml");
        saveResourceIfMissing("is/is_top.yml");
        saveResourceIfMissing("core/core.yml");
        saveResourceIfMissing("core/core_menu.yml");
        saveResourceIfMissing("core/core_upgrades.yml");
        saveResourceIfMissing("core/astral_tree.yml");
        saveResourceIfMissing("core/pylons.yml");
        saveResourceIfMissing("core/condenser.yml");
        saveResourceIfMissing("core/crop_generator.yml");

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

        sounds = new IslandSounds(this);
        islandService = new IslandService(this, islandWorld, islandRepository, messages, sounds);
        try {
            coreRepository = new CoreRepository(new File(getDataFolder(), "data/cores.yml"), this);
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
            condenserService = new CondenserService(
                    this,
                    islandService,
                    coreService,
                    coreRepository,
                    new CondenserRepository(new File(getDataFolder(), "data/condensers.yml"), this),
                    pylonService,
                    messages,
                    new File(getDataFolder(), "core/condenser.yml"));
            coreService.attachCondenserService(condenserService);
            cropGeneratorService = new CropGeneratorService(
                    this,
                    islandService,
                    coreService,
                    coreRepository,
                    new CropGeneratorRepository(new File(getDataFolder(), "data/crop_generators.yml"), this),
                    pylonService,
                    condenserService,
                    messages,
                    new File(getDataFolder(), "core/crop_generator.yml"));
            coreService.attachCropGeneratorService(cropGeneratorService);
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Could not initialize the core modules: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        menus = new IslandMenuManager(this, islandRepository, islandService, messages, sounds);
        topMenu = new IslandTopMenu(this, islandRepository, coreRepository, messages);
        CoreCommand coreCommand = new CoreCommand(this, islandService, islandRepository, messages, menus, topMenu);
        if (getCommand("is") == null) {
            getLogger().severe("The /is command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (getCommand("core") == null) {
            getLogger().severe("The /core command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("is").setExecutor(coreCommand);
        getCommand("is").setTabCompleter(coreCommand);
        getCommand("core").setExecutor(coreCommand);
        getCommand("core").setTabCompleter(coreCommand);
        getServer().getPluginManager().registerEvents(
                new IslandListener(this, islandService, messages), this);
        getServer().getPluginManager().registerEvents(
                new CorePlaceListener(this, coreService, pylonService, condenserService, cropGeneratorService), this);
        getServer().getPluginManager().registerEvents(pylonService.menuListener(), this);
        getServer().getPluginManager().registerEvents(condenserService, this);
        getServer().getPluginManager().registerEvents(condenserService.menuListener(), this);
        getServer().getPluginManager().registerEvents(condenserService.runeMenuListener(), this);
        getServer().getPluginManager().registerEvents(cropGeneratorService, this);
        getServer().getPluginManager().registerEvents(cropGeneratorService.menuListener(), this);
        getServer().getPluginManager().registerEvents(cropGeneratorService.runeMenuListener(), this);
        getServer().getPluginManager().registerEvents(coreService.menuListener(), this);
        getServer().getPluginManager().registerEvents(coreService.upgradesMenuListener(), this);
        getServer().getPluginManager().registerEvents(coreService.astralTreeMenuListener(), this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(topMenu, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new CoreboundPlaceholderExpansion(this, islandRepository, coreRepository);
            if (placeholderExpansion.register()) {
                getLogger().info("Registered PlaceholderAPI expansion.");
            } else {
                getLogger().severe("Could not register the PlaceholderAPI expansion.");
            }
        }
        coreService.initializeLoadedEntities();
        pylonService.initializeLoadedEntities();
        condenserService.initializeLoadedEntities();
        cropGeneratorService.initializeLoadedEntities();
        pylonService.initializeRangeGuides();
        getLogger().info("Corebound island foundation enabled.");
    }

    @Override
    public void onDisable() {
        if (placeholderExpansion != null) {
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                placeholderExpansion.unregister();
            }
            placeholderExpansion = null;
        }
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
            if (condenserService != null) {
                try {
                    condenserService.flush();
                } catch (IOException exception) {
                    getLogger().severe("Could not save condenser data: " + exception.getMessage());
                }
            }
            if (cropGeneratorService != null) {
                try {
                    cropGeneratorService.flush();
                } catch (IOException exception) {
                    getLogger().severe("Could not save crop generator data: " + exception.getMessage());
                }
            }
        }
    }

    private void validateIslandSpacing() throws IOException {
        validateIslandSpacing(getConfig());
    }

    public void reloadConfigurations() throws IOException {
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(new File(getDataFolder(), "config.yml"));
        } catch (org.bukkit.configuration.InvalidConfigurationException exception) {
            throw new IOException("config.yml contains invalid YAML.", exception);
        }
        validateIslandSpacing(candidate);
        String configuredWorld = candidate.getString("world.name", "core_void_world");
        if (!islandService.islandWorld().getName().equals(configuredWorld)) {
            throw new IllegalArgumentException("Changing world.name requires a server restart.");
        }

        reloadConfig();
        validateIslandSpacing();
        islandService.reloadConfiguration();
        messages.reload();
        sounds.reload();
        menus.reload();
        topMenu.reload();
        coreService.reloadConfiguration();
        pylonService.reloadConfiguration();
        condenserService.reloadConfiguration();
        cropGeneratorService.reloadConfiguration();
    }

    private void validateIslandSpacing(org.bukkit.configuration.ConfigurationSection config) throws IOException {
        long spacing = config.getLong("world.distance-between-islands");
        double radius = config.getDouble("world.island-radius");
        if (spacing <= 0 || !Double.isFinite(radius) || radius <= 0 || spacing <= radius * 2) {
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
