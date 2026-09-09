package dev.infinia.store.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ListingNameMigrationTest {
    @Test
    void wideningPreservesNamesAndCanFollowAProductionHotfix() throws Exception {
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:listing-name-migration;MODE=PostgreSQL");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE listing_i18n (name VARCHAR(100) NOT NULL)");
            statement.execute("INSERT INTO listing_i18n VALUES ('Existing name')");
            String longName = "上游商品名称".repeat(60);
            try (var insert = connection.prepareStatement("INSERT INTO listing_i18n VALUES (?)")) {
                insert.setString(1, longName);
                assertThrows(SQLException.class, insert::executeUpdate);
                try (var resource = getClass().getResourceAsStream(
                        "/db/migration/V13__listing_name_text.sql")) {
                    assertNotNull(resource);
                    String migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                    statement.execute(migration);
                    statement.execute(migration);
                }
                assertEquals(1, insert.executeUpdate());
            }
            try (var rows = statement.executeQuery("SELECT name FROM listing_i18n ORDER BY LENGTH(name)")) {
                assertTrue(rows.next());
                assertEquals("Existing name", rows.getString(1));
                assertTrue(rows.next());
                assertEquals(longName, rows.getString(1));
                assertFalse(rows.next());
            }
        }
    }
}
