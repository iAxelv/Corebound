package com.corebound.thecore;

import com.corebound.thecore.command.CoreCommand;
import com.corebound.thecore.island.IslandRepository;
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
    private Messages messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages_es.yml", false);

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
                new File(getDataFolder(), "messages_es.yml"), getResource("messages_es.yml"));
        islandRepository = new IslandRepository(new File(getDataFolder(), "data/islands.yml"));

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

        islandService = new IslandService(this, islandWorld, islandRepository, messages);
        CoreCommand coreCommand = new CoreCommand(this, islandService, islandRepository, messages);
        if (getCommand("is") == null) {
            getLogger().severe("The /is command is missing from plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("is").setExecutor(coreCommand);
        getCommand("is").setTabCompleter(coreCommand);
        getServer().getPluginManager().registerEvents(
                new IslandListener(this, islandService, messages), this);
        getLogger().info("TheCore island foundation enabled.");
    }

    @Override
    public void onDisable() {
        if (islandRepository != null) {
            try {
                islandRepository.save();
            } catch (IOException exception) {
                getLogger().severe("Could not save island data: " + exception.getMessage());
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
}
