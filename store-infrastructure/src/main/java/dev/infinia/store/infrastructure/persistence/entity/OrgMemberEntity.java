package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "organization_member")
@IdClass(OrgMemberEntity.MemberId.class)
public class OrgMemberEntity {
    @Id
    @Column(name = "organization_id")
    public UUID organizationId;
    @Id
    @Column(name = "user_id")
    public UUID userId;
    @Column(name = "role", nullable = false)
    public String role;
    @Column(name = "joined_at", nullable = false)
    public Instant joinedAt;

    public static class MemberId implements Serializable {
        public UUID organizationId;
        public UUID userId;

        public MemberId() {
        }

        public MemberId(UUID organizationId, UUID userId) {
            this.organizationId = organizationId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MemberId m
                    && Objects.equals(organizationId, m.organizationId)
                    && Objects.equals(userId, m.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, userId);
        }
    }
}
