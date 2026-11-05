package com.corebound.thecore.incremental;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class EssenceRepository {
    public record Balance(BigDecimal mineral, BigDecimal vegetal) {
        public Balance {
            mineral = mineral.max(BigDecimal.ZERO);
            vegetal = vegetal.max(BigDecimal.ZERO);
        }
    }

    private final File file;
    private final Map<UUID, Balance> balances = new HashMap<>();

    public EssenceRepository(File file) {
        this.file = file;
        load();
    }

    public synchronized Balance balance(UUID playerId) {
        return balances.getOrDefault(playerId, new Balance(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    public synchronized void deposit(UUID playerId, BigDecimal mineral, BigDecimal vegetal) throws IOException {
        if (mineral.signum() < 0 || vegetal.signum() < 0) {
            throw new IllegalArgumentException("Essence deposits must not be negative.");
        }
        Balance previous = balance(playerId);
        Balance updated = new Balance(previous.mineral().add(mineral), previous.vegetal().add(vegetal));
        balances.put(playerId, updated);
        try {
            save();
        } catch (IOException exception) {
            if (previous.mineral().signum() == 0 && previous.vegetal().signum() == 0) {
                balances.remove(playerId);
            } else {
                balances.put(playerId, previous);
            }
            throw exception;
        }
    }

    public synchronized void flush() throws IOException {
        save();
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Balance> entry : balances.entrySet()) {
            String path = "balances." + entry.getKey();
            yaml.set(path + ".mineral", entry.getValue().mineral().toPlainString());
            yaml.set(path + ".vegetal", entry.getValue().vegetal().toPlainString());
        }
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "essences-", ".yml.tmp");
        try {
            Files.writeString(temporary, yaml.saveToString());
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("balances");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection balance = section.getConfigurationSection(id);
            if (balance == null) {
                continue;
            }
            balances.put(UUID.fromString(id), new Balance(
                    new BigDecimal(balance.getString("mineral", "0")),
                    new BigDecimal(balance.getString("vegetal", "0"))));
        }
    }
}
