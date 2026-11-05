package com.corebound.thecore.gui;

import com.corebound.thecore.config.ConfigFiles;
import com.corebound.thecore.incremental.CoreRecord;
import com.corebound.thecore.incremental.CoreRepository;
import com.corebound.thecore.island.IslandData;
import com.corebound.thecore.island.IslandRank;
import com.corebound.thecore.island.IslandRepository;
import com.corebound.thecore.message.Messages;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

public final class IslandTopMenu implements Listener {
    private static final Pattern SKIN_URL =
            Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"\\\\]+)\"");
    private static final List<Integer> DEFAULT_RANK_SLOTS =
            List.of(13, 21, 22, 23, 29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42, 43);

    private enum SortMode {
        CORE_LEVEL("core-level"),
        ENERGY("energy"),
        FRACTURE("fracture"),
        HITS("hits");

        private final String key;

        SortMode(String key) {
            this.key = key;
        }

        private String messageKey() {
            return "is-top.sort." + key;
        }
    }

    private static final class TopHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record RankedIsland(IslandData island, CoreRecord core) {
    }

    private record RankedMember(UUID playerId, IslandRank rank) {
    }

    private final JavaPlugin plugin;
    private final IslandRepository islands;
    private final CoreRepository cores;
    private final Messages messages;
    private final java.io.File configFile;
    private YamlConfiguration config;
    private URL defaultSkinUrl;

    public IslandTopMenu(
            JavaPlugin plugin, IslandRepository islands, CoreRepository cores, Messages messages) {
        this.plugin = plugin;
        this.islands = islands;
        this.cores = cores;
        this.messages = messages;
        configFile = new java.io.File(plugin.getDataFolder(), "is/is_top.yml");
        reload();
    }

    public void reload() {
        YamlConfiguration loaded = ConfigFiles.load(configFile, plugin.getResource("is/is_top.yml"));
        validate(loaded);
        URL loadedSkinUrl = skinUrl(loaded.getString("default-head-texture"));
        config = loaded;
        defaultSkinUrl = loadedSkinUrl;
    }

    public void open(Player player) {
        open(player, SortMode.CORE_LEVEL);
    }

    private void open(Player player, SortMode sortMode) {
        int rows = config.getInt("rows");
        TopHolder holder = new TopHolder();
        holder.inventory = Bukkit.createInventory(
                holder,
                rows * 9,
                text(messages.legacy("is-top.title")));

        setInfoItem(holder.inventory, player, sortMode);
        setSortItems(holder.inventory, sortMode);
        setRankedIslands(holder.inventory, sortMode);
        setCloseItem(holder.inventory);
        player.openInventory(holder.inventory);
    }

    private void setInfoItem(Inventory inventory, Player player, SortMode sortMode) {
        IslandData island = islands.forPlayer(player.getUniqueId()).orElse(null);
        CoreRecord core = island == null ? null : cores.core(island.index());
        Map<String, String> values = Map.of(
                "island", island == null ? messages.text("is-top.no-island") : island.name(),
                "level", core == null ? "0" : Integer.toString(core.level()),
                "energy", island == null ? "0" : formatEnergy(cores.generatedEnergy(island.index())),
                "fracture", core == null ? "0" : Integer.toString(core.prestigeCount()),
                "section", messages.text(sortMode.messageKey()));
        List<String> lore = List.of(
                messages.formatText("is-top.info-island", values),
                messages.formatText("is-top.info-level", values),
                messages.formatText("is-top.info-energy", values),
                messages.formatText("is-top.info-fracture", values),
                messages.formatText("is-top.info-section", values));
        if (island == null) {
            lore = new ArrayList<>(lore);
            lore.add(messages.legacy("is-top.info-no-island"));
        }
        setConfiguredItem(
                inventory,
                "info.material",
                config.getInt("info.slot"),
                messages.legacy("is-top.info-name"),
                lore);
    }

    private void setSortItems(Inventory inventory, SortMode selected) {
        ConfigurationSection sorters = config.getConfigurationSection("sorters");
        for (SortMode mode : SortMode.values()) {
            ConfigurationSection sorter = sorters.getConfigurationSection(mode.key);
            int slot = sorter.getInt("slot");
            String prefix = selected == mode
                    ? messages.legacy("is-top.sort-selected-prefix")
                    : messages.legacy("is-top.sort-unselected-prefix");
            String displayName = prefix + messages.legacy(mode.messageKey());
            List<String> lore = List.of(messages.legacy(
                    selected == mode ? "is-top.sort-selected-lore" : "is-top.sort-unselected-lore"));
            setConfiguredItem(inventory, "sorters." + mode.key + ".material", slot, displayName, lore);
        }
    }

    private void setRankedIslands(Inventory inventory, SortMode sortMode) {
        List<RankedIsland> ranked = islands.all().stream()
                .map(island -> new RankedIsland(island, cores.core(island.index())))
                .sorted(comparator(sortMode))
                .limit(DEFAULT_RANK_SLOTS.size())
                .toList();
        List<Integer> slots = config.getIntegerList("rank-slots");
        for (int i = 0; i < slots.size(); i++) {
            int rank = i + 1;
            if (i < ranked.size()) {
                setIslandHead(inventory, slots.get(i), rank, ranked.get(i), sortMode);
            } else {
                setEmptyHead(inventory, slots.get(i), rank);
            }
        }
    }

    private Comparator<RankedIsland> comparator(SortMode sortMode) {
        return Comparator.comparingDouble((RankedIsland entry) -> score(entry, sortMode))
                .reversed()
                .thenComparing(entry -> entry.island().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.island().name());
    }

    private double score(RankedIsland entry, SortMode sortMode) {
        return switch (sortMode) {
            case CORE_LEVEL -> entry.core() == null ? 0 : entry.core().level();
            case ENERGY -> cores.generatedEnergy(entry.island().index());
            case FRACTURE -> entry.core() == null ? 0 : entry.core().prestigeCount();
            case HITS -> cores.topHits(entry.island().index());
        };
    }

    private void setIslandHead(
            Inventory inventory, int slot, int rank, RankedIsland entry, SortMode sortMode) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(entry.island().owner());
        meta.setOwningPlayer(owner);
        meta.displayName(text(messages.formatText("is-top.island-name", Map.of(
                "rank", Integer.toString(rank),
                "island", entry.island().name(),
                "owner", playerName(entry.island().owner())))));
        List<String> lore = new ArrayList<>();
        lore.add(messages.formatText(statKey(sortMode), Map.of("value", scoreText(entry, sortMode))));
        lore.add(messages.legacy("is-top.separator"));
        lore.add(messages.legacy("is-top.members"));
        for (RankedMember member : members(entry.island())) {
            lore.add(messages.formatText("is-top.member", Map.of(
                    "player", playerName(member.playerId()),
                    "rank", messages.legacy(member.rank().messageKey()))));
        }
        meta.lore(lore.stream().map(this::text).toList());
        head.setItemMeta(meta);
        inventory.setItem(slot, head);
    }

    private void setEmptyHead(Inventory inventory, int slot, int rank) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
        PlayerTextures textures = profile.getTextures();
        textures.setSkin(defaultSkinUrl);
        profile.setTextures(textures);
        meta.setOwnerProfile(profile);
        meta.displayName(text(messages.formatText("is-top.empty-name", Map.of(
                "rank", Integer.toString(rank)))));
        meta.lore(List.of(text(messages.legacy("is-top.empty-lore"))));
        head.setItemMeta(meta);
        inventory.setItem(slot, head);
    }

    private List<RankedMember> members(IslandData island) {
        List<RankedMember> members = new ArrayList<>();
        members.add(new RankedMember(island.owner(), IslandRank.OWNER));
        island.members().keySet().forEach(member ->
                members.add(new RankedMember(member, island.rank(member))));
        members.sort(Comparator.comparingInt((RankedMember member) -> member.rank().level()).reversed()
                .thenComparing(member -> playerName(member.playerId()), String.CASE_INSENSITIVE_ORDER));
        return members;
    }

    private String scoreText(RankedIsland entry, SortMode sortMode) {
        return switch (sortMode) {
            case CORE_LEVEL -> entry.core() == null ? "0" : Integer.toString(entry.core().level());
            case ENERGY -> formatEnergy(cores.generatedEnergy(entry.island().index()));
            case FRACTURE -> entry.core() == null ? "0" : Integer.toString(entry.core().prestigeCount());
            case HITS -> Long.toString(cores.topHits(entry.island().index()));
        };
    }

    private String statKey(SortMode sortMode) {
        return "is-top.stat." + sortMode.key;
    }

    private void setCloseItem(Inventory inventory) {
        setConfiguredItem(
                inventory,
                "close.material",
                config.getInt("close.slot"),
                messages.legacy("is-top.close-name"),
                List.of(messages.legacy("is-top.close-lore")));
    }

    private void setConfiguredItem(Inventory inventory, String materialPath, int slot, String name, List<String> lore) {
        Material material = Material.matchMaterial(config.getString(materialPath));
        if (material == null) {
            throw new IllegalArgumentException("Invalid material in is_top.yml: " + materialPath);
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name));
        meta.lore(lore.stream().map(this::text).toList());
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private String playerName(UUID playerId) {
        return Optional.ofNullable(Bukkit.getOfflinePlayer(playerId).getName()).orElse(playerId.toString());
    }

    private String formatEnergy(double energy) {
        if (energy >= 1_000_000_000) {
            return String.format(Locale.ROOT, "%.1fB", energy / 1_000_000_000);
        }
        if (energy >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", energy / 1_000_000);
        }
        if (energy >= 1_000) {
            return String.format(Locale.ROOT, "%.1fK", energy / 1_000);
        }
        return String.format(Locale.ROOT, "%.1f", energy);
    }

    private URL skinUrl(String encodedTexture) {
        try {
            String json = new String(Base64.getDecoder().decode(encodedTexture), java.nio.charset.StandardCharsets.UTF_8);
            Matcher matcher = SKIN_URL.matcher(json);
            if (!matcher.find()) {
                throw new IllegalArgumentException("The default head texture in is_top.yml has no skin URL.");
            }
            return URI.create(matcher.group(1)).toURL();
        } catch (IllegalArgumentException | MalformedURLException exception) {
            throw new IllegalArgumentException("The default head texture in is_top.yml is invalid.", exception);
        }
    }

    private void validate(YamlConfiguration loaded) {
        int rows = loaded.getInt("rows", 6);
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("is_top.yml rows must be between 1 and 6.");
        }
        int size = rows * 9;
        List<Integer> rankSlots = loaded.getIntegerList("rank-slots");
        if (rankSlots.size() != DEFAULT_RANK_SLOTS.size()) {
            throw new IllegalArgumentException("is_top.yml must define exactly 16 rank slots.");
        }
        Set<Integer> usedSlots = new HashSet<>();
        for (int slot : rankSlots) {
            validateSlot(slot, size, "rank-slots");
            if (!usedSlots.add(slot)) {
                throw new IllegalArgumentException("is_top.yml contains duplicate menu slots.");
            }
        }
        addSlot(loaded, "info.slot", size, usedSlots);
        addSlot(loaded, "close.slot", size, usedSlots);
        ConfigurationSection sorters = loaded.getConfigurationSection("sorters");
        if (sorters == null) {
            throw new IllegalArgumentException("is_top.yml is missing sorters.");
        }
        for (SortMode mode : SortMode.values()) {
            ConfigurationSection sorter = sorters.getConfigurationSection(mode.key);
            if (sorter == null) {
                throw new IllegalArgumentException("is_top.yml is missing sorter " + mode.key + ".");
            }
            addSlot(loaded, "sorters." + mode.key + ".slot", size, usedSlots);
            validateMaterial(loaded, "sorters." + mode.key + ".material");
        }
        validateMaterial(loaded, "info.material");
        validateMaterial(loaded, "close.material");
        String encodedTexture = loaded.getString("default-head-texture", "");
        if (encodedTexture.isBlank()) {
            throw new IllegalArgumentException("is_top.yml default-head-texture cannot be empty.");
        }
    }

    private void addSlot(YamlConfiguration loaded, String path, int size, Set<Integer> usedSlots) {
        int slot = loaded.getInt(path, -1);
        validateSlot(slot, size, path);
        if (!usedSlots.add(slot)) {
            throw new IllegalArgumentException("is_top.yml contains duplicate menu slots.");
        }
    }

    private void validateSlot(int slot, int size, String path) {
        if (slot < 0 || slot >= size) {
            throw new IllegalArgumentException("is_top.yml " + path + " must be within the inventory.");
        }
    }

    private void validateMaterial(YamlConfiguration loaded, String path) {
        if (Material.matchMaterial(loaded.getString(path, "")) == null) {
            throw new IllegalArgumentException("Invalid material in is_top.yml: " + path);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof TopHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (event.getRawSlot() == config.getInt("close.slot")) {
            player.closeInventory();
            return;
        }
        ConfigurationSection sorters = config.getConfigurationSection("sorters");
        for (SortMode mode : SortMode.values()) {
            if (sorters.getInt(mode.key + ".slot", -1) == event.getRawSlot()) {
                open(player, mode);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof TopHolder) {
            event.setCancelled(true);
        }
    }

    private Component text(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value)
                .decoration(TextDecoration.ITALIC, false);
    }
}
