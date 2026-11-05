package com.corebound.thecore.placeholder;

import com.corebound.thecore.TheCorePlugin;
import com.corebound.thecore.incremental.CoreRepository;
import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandRepository;
import java.util.Locale;
import java.util.Optional;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CoreboundPlaceholderExpansion extends PlaceholderExpansion {
    private final TheCorePlugin plugin;
    private final IslandRepository islands;
    private final CoreRepository cores;

    public CoreboundPlaceholderExpansion(
            TheCorePlugin plugin, IslandRepository islands, CoreRepository cores) {
        this.plugin = plugin;
        this.islands = islands;
        this.cores = cores;
    }

    @Override
    @NotNull
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "thecore";
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    @Nullable
    public String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        Optional<IslandData> island = islands.forPlayer(player.getUniqueId());
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "island_name" -> island.map(IslandData::name).orElse("");
            case "core_energy" -> island
                    .map(data -> cores.core(data.index()))
                    .map(core -> PlaceholderEnergyFormat.format(core.energy()))
                    .orElse("0");
            case "island_energy_collected" -> island
                    .map(data -> PlaceholderEnergyFormat.format(cores.generatedEnergy(data.index())))
                    .orElse("0");
            case "core_hits" -> island
                    .map(data -> Long.toString(cores.topHits(data.index())))
                    .orElse("0");
            default -> null;
        };
    }
}
