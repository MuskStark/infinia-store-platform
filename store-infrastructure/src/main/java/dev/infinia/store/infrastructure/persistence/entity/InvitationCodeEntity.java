package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation_code")
public class InvitationCodeEntity {
    @Id
    @Column(name = "id", nullable = false)
    public UUID id;
    @Column(name = "code", nullable = false)
    public String code;
    @Column(name = "created_by", nullable = false)
    public UUID createdBy;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
    @Column(name = "used_by")
    public UUID usedBy;
    @Column(name = "used_at")
    public Instant usedAt;
}
