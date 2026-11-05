package com.corebound.thecore.incremental;

import com.corebound.thecore.config.ConfigFiles;
import com.corebound.thecore.message.Messages;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class EssenceService {
    public enum Type {
        ORES,
        VEGETALS
    }

    public record ItemQuote(Material material, String name, int amount, BigDecimal essence, Type type) {
    }

    public record Quote(List<ItemQuote> items, BigDecimal mineral, BigDecimal vegetal) {
        public Quote {
            items = List.copyOf(items);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    private record Value(String name, Type type, BigDecimal amount) {
    }

    private final JavaPlugin plugin;
    private final Messages messages;
    private final EssenceRepository repository;
    private final File valuesFile;
    private Map<Material, Value> values = Map.of();

    public EssenceService(
            JavaPlugin plugin,
            Messages messages,
            EssenceRepository repository,
            File valuesFile) {
        this.plugin = plugin;
        this.messages = messages;
        this.repository = repository;
        this.valuesFile = valuesFile;
        reload();
    }

    public void reload() {
        YamlConfiguration loaded = ConfigFiles.load(valuesFile);
        InputStream defaultsStream = plugin.getResource("values.yml");
        if (defaultsStream == null) {
            throw new IllegalArgumentException("Bundled values.yml is missing.");
        }
        try (InputStreamReader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(reader));
            loaded.options().copyDefaults(true);
            loaded.save(valuesFile);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not load values.yml defaults.", exception);
        }
        ConfigurationSection items = loaded.getConfigurationSection("items");
        if (items == null) {
            throw new IllegalArgumentException("values.yml items must be configured.");
        }
        Map<Material, Value> configured = new LinkedHashMap<>();
        for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key);
            if (item == null) {
                throw new IllegalArgumentException("values.yml items." + key + " must be a section.");
            }
            Material material = Material.matchMaterial(item.getString("material", key));
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("values.yml items." + key + ".material must be a valid item.");
            }
            Type type;
            try {
                type = Type.valueOf(item.getString("type", "").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "values.yml items." + key + ".type must be ores or vegetals.", exception);
            }
            BigDecimal amount;
            try {
                Object configuredAmount = item.get("value");
                amount = new BigDecimal(configuredAmount == null ? "" : configuredAmount.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("values.yml items." + key + ".value must be positive.", exception);
            }
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("values.yml items." + key + ".value must be positive.");
            }
            String name = item.getString("name");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("values.yml items." + key + ".name must not be empty.");
            }
            if (configured.put(material, new Value(name, type, amount)) != null) {
                throw new IllegalArgumentException("values.yml contains duplicate material " + material + ".");
            }
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(configured));
    }

    public Quote quote(Player player) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir() && values.containsKey(item.getType())) {
                counts.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        List<ItemQuote> items = new ArrayList<>();
        BigDecimal mineral = BigDecimal.ZERO;
        BigDecimal vegetal = BigDecimal.ZERO;
        for (Map.Entry<Material, Value> entry : values.entrySet()) {
            int amount = counts.getOrDefault(entry.getKey(), 0);
            if (amount == 0) {
                continue;
            }
            Value value = entry.getValue();
            BigDecimal essence = value.amount().multiply(BigDecimal.valueOf(amount));
            items.add(new ItemQuote(entry.getKey(), value.name(), amount, essence, value.type()));
            if (value.type() == Type.ORES) {
                mineral = mineral.add(essence);
            } else {
                vegetal = vegetal.add(essence);
            }
        }
        return new Quote(items, mineral, vegetal);
    }

    public boolean sell(Player player) {
        Quote quote = quote(player);
        if (quote.isEmpty()) {
            messages.send(player, "essence-no-items");
            return false;
        }
        try {
            repository.deposit(player.getUniqueId(), quote.mineral(), quote.vegetal());
        } catch (IOException exception) {
            plugin.getLogger().log(
                    Level.SEVERE, "Could not save essence sale for " + player.getUniqueId() + ".", exception);
            messages.send(player, "essence-sale-failed");
            return false;
        }
        int storageSize = player.getInventory().getStorageContents().length;
        for (int slot = 0; slot < storageSize; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item != null && values.containsKey(item.getType())) {
                player.getInventory().setItem(slot, null);
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.5f);
        messages.send(player, "essence-sale-success", Map.of(
                "mineral", format(quote.mineral()),
                "vegetal", format(quote.vegetal()),
                "mineral_name", messages.text("essence.mineral-name"),
                "vegetal_name", messages.text("essence.vegetal-name")));
        return true;
    }

    public EssenceRepository.Balance balance(UUID playerId) {
        return repository.balance(playerId);
    }

    public String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    public void flush() throws IOException {
        repository.flush();
    }
}
