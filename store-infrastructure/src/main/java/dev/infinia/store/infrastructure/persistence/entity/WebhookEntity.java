package dev.infinia.store.infrastructure.persistence.entity;

import dev.infinia.store.infrastructure.persistence.converter.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "webhook")
public class WebhookEntity {
    @Id
    public UUID id;
    @Column(name = "organization_id", nullable = false)
    public UUID organizationId;
    @Column(name = "url", nullable = false)
    public String url;
    @Column(name = "secret", nullable = false)
    public String secret;
    @Convert(converter = StringListConverter.class)
    @Column(name = "events", length = 1000)
    public List<String> events = new ArrayList<>();
    @Column(name = "active", nullable = false)
    public boolean active;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
