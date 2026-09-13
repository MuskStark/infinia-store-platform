package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "system_setting")
public class SystemSettingEntity {
    @Id
    @Column(name = "setting_key", nullable = false)
    public String settingKey;
    @Column(name = "setting_value", nullable = false)
    public String settingValue;
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
