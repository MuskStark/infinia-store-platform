package dev.infinia.store.infrastructure.persistence.repository;

import dev.infinia.store.infrastructure.persistence.entity.ListingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingJpaRepository extends JpaRepository<ListingEntity, UUID> {

    Optional<ListingEntity> findByNamespaceIdAndSlugAndType(UUID namespaceId, String slug, String type);

    List<ListingEntity> findByPublisherUserId(UUID publisherUserId);

    @Query("SELECT l FROM ListingEntity l WHERE (:type IS NULL OR l.type = :type)")
    List<ListingEntity> findAllByOptionalType(@Param("type") String type);
}
