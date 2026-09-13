package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.port.InvitationRepositories;
import dev.infinia.store.infrastructure.persistence.entity.SystemSettingEntity;
import dev.infinia.store.infrastructure.persistence.repository.SystemSettingJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
public class SystemSettingAdapter
        implements InvitationRepositories.SystemSettingRepository {

    private final SystemSettingJpaRepository jpa;

    public SystemSettingAdapter(SystemSettingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<String> find(String key) {
        return jpa.findById(key).map(e -> e.settingValue);
    }

    @Override
    @Transactional
    public void save(String key, String value) {
        SystemSettingEntity e = jpa.findById(key).orElseGet(SystemSettingEntity::new);
        e.settingKey = key;
        e.settingValue = value;
        e.updatedAt = Instant.now();
        jpa.save(e);
    }
}
