package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "remote_database")
public class RemoteDatabaseEntity {
    @Id
    public UUID id;
    @Column(nullable = false, length = 100)
    public String name;
    @Column(name = "jdbc_url", nullable = false, length = 500)
    public String jdbcUrl;
    @Column(nullable = false, length = 200)
    public String username;
    /** AES-GCM sealed password — never exposed through the API. */
    @Column(name = "password_cipher", nullable = false, length = 2000)
    public String passwordCipher;
    @Column(nullable = false)
    public Boolean enabled;
    @Column(name = "last_tested_at")
    public Instant lastTestedAt;
    @Column(name = "last_test_ok")
    public Boolean lastTestOk;
    @Column(name = "last_test_error", length = 1000)
    public String lastTestError;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
