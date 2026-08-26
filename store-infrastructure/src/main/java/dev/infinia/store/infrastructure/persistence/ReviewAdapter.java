package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.infrastructure.persistence.entity.ReviewEntity;
import dev.infinia.store.infrastructure.persistence.repository.ReviewJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReviewAdapter implements PublishingRepositories.ReviewRepository {

    private final ReviewJpaRepository jpa;

    public ReviewAdapter(ReviewJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Review> findById(UUID id) {
        return jpa.findById(id).map(ReviewAdapter::toDomain);
    }

    @Override
    public Optional<Review> findLatestByReleaseId(UUID releaseId) {
        return jpa.findTopByReleaseIdOrderBySubmittedAtDesc(releaseId)
                .map(ReviewAdapter::toDomain);
    }

    @Override
    @Transactional
    public void save(Review review) {
        ReviewEntity e = jpa.findById(review.id).orElseGet(ReviewEntity::new);
        e.id = review.id;
        e.releaseId = review.releaseId;
        e.listingId = review.listingId;
        e.status = review.status;
        e.reviewerId = review.reviewerId;
        e.notes = review.notes;
        e.submittedAt = review.submittedAt;
        e.decidedAt = review.decidedAt;
        e.findings.clear();
        for (Review.Finding f : review.findings) {
            e.findings.add(new ReviewEntity.FindingEmb(f.severity(), f.rule(), f.message()));
        }
        jpa.save(e);
    }

    @Override
    public List<Review> findByStatus(String status, int limit) {
        return jpa.findTop100ByStatusOrderBySubmittedAtDesc(status).stream()
                .limit(limit)
                .map(ReviewAdapter::toDomain)
                .toList();
    }

    static Review toDomain(ReviewEntity e) {
        Review r = new Review();
        r.id = e.id;
        r.releaseId = e.releaseId;
        r.listingId = e.listingId;
        r.status = e.status;
        r.reviewerId = e.reviewerId;
        r.notes = e.notes;
        r.submittedAt = e.submittedAt;
        r.decidedAt = e.decidedAt;
        r.findings = new ArrayList<>();
        for (ReviewEntity.FindingEmb f : e.findings) {
            r.findings.add(new Review.Finding(f.severity(), f.rule(), f.message()));
        }
        return r;
    }
}
