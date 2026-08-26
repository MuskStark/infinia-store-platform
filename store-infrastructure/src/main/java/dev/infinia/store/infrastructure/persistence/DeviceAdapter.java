package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.Device;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.infrastructure.persistence.entity.DeviceEntity;
import dev.infinia.store.infrastructure.persistence.repository.DeviceJpaRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class DeviceAdapter implements IdentityRepositories.DeviceRepository {

    private final DeviceJpaRepository jpa;

    public DeviceAdapter(DeviceJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<Device> findByUserId(UUID userId) {
        return jpa.findByUserId(userId).stream().map(DeviceAdapter::toDomain).toList();
    }

    @Override
    public Optional<Device> findById(UUID id) {
        return jpa.findById(id).map(DeviceAdapter::toDomain);
    }

    @Override
    public void save(Device device) {
        DeviceEntity e = jpa.findById(device.id()).orElseGet(DeviceEntity::new);
        e.id = device.id();
        e.userId = device.userId();
        e.publicId = device.publicId();
        e.name = device.name();
        e.platform = device.platform();
        e.createdAt = device.createdAt();
        e.lastSeenAt = device.lastSeenAt();
        e.revoked = device.revoked();
        jpa.save(e);
    }

    private static Device toDomain(DeviceEntity e) {
        return new Device(e.id, e.userId, e.publicId, e.name, e.platform, e.createdAt,
                e.lastSeenAt, e.revoked);
    }
}
