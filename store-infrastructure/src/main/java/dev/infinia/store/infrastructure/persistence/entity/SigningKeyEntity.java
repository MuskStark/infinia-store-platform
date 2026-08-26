package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "signing_key")
public class SigningKeyEntity {
    @Id
    @Column(name = "key_id")
    public String keyId;
    @Column(name = "algorithm", nullable = false)
    public String algorithm;
    @Column(name = "public_key_base64", nullable = false)
    public String publicKeyBase64;
    @Column(name = "owner_type", nullable = false)
    public String ownerType;
    @Column(name = "owner_ref")
    public String ownerRef;
    @Column(name = "status", nullable = false)
    public String status;
    @Column(name = "valid_from")
    public Instant validFrom;
    @Column(name = "valid_to")
    public Instant validTo;
}
