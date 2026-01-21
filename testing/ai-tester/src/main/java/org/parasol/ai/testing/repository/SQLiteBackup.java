package org.parasol.ai.testing.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.agroal.api.AgroalDataSource;

import io.quarkus.runtime.ShutdownEvent;

@ApplicationScoped
public class SQLiteBackup {
    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @Inject
    AgroalDataSource dataSource;

    // Execute a backup during shutdown
    public void onShutdown(@Observes ShutdownEvent event) {
			backup();
		}

    private void backup() {
        String dbFile = jdbcUrl.substring("jdbc:sqlite:".length());

        var originalDbFilePath = Paths.get(dbFile);
        var backupDbFilePath = originalDbFilePath
                                    .toAbsolutePath()
                                    .getParent()
                                    .resolve(originalDbFilePath.getFileName() + "_backup");

        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {

            // Execute the backup
            stmt.executeUpdate("backup to " + backupDbFilePath);
            // Atomically replace the DB file with its backup
            Files.move(backupDbFilePath, originalDbFilePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SQLException e) {
            throw new RuntimeException("Failed to back up the database", e);
        }
    }
}
