package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upstream_source")
public class UpstreamSourceEntity {
    @Id
    public UUID id;
    @Column(nullable = false, length = 100)
    public String name;
    @Column(name = "marketplace_url", nullable = false, length = 1024)
    public String marketplaceUrl;
    @Column(name = "target_namespace", nullable = false, length = 63)
    public String targetNamespace;
    @Column(nullable = false)
    public Boolean enabled;
    @Column(name = "last_sync_at")
    public Instant lastSyncAt;
    @Column(name = "last_sync_ok")
    public Boolean lastSyncOk;
    @Column(name = "last_error", length = 1000)
    public String lastError;
    @Column(name = "adapter_type", length = 40)
    public String adapterType;
}
