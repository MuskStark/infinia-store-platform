package dev.infinia.monitor.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One component's aggregated probe outcome for one UTC day — the same bucket
 * shape the store uses for its own history, kept locally for the components
 * the monitor owns (external reachability). Because the store's own 90-day
 * history ships inside every mirrored snapshot, the monitor only accumulates
 * buckets for what it observes itself.
 */
@Entity
@Table(name = "external_uptime_day")
@IdClass(ExternalDayEntity.Key.class)
public class ExternalDayEntity {

    public static class Key implements Serializable {
        public String component;
        public LocalDate day;

        public Key() {}

        public Key(String component, LocalDate day) {
            this.component = component;
            this.day = day;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && component.equals(other.component) && day.equals(other.day);
        }

        @Override
        public int hashCode() {
            return component.hashCode() * 31 + day.hashCode();
        }
    }

    @Id
    public String component;
    @Id
    @Column(name = "sample_day")
    public LocalDate day;
    @Column(nullable = false)
    public long ok;
    @Column(nullable = false)
    public long degraded;
    @Column(nullable = false)
    public long down;

    public static ExternalDayEntity fresh(String component, LocalDate day) {
        ExternalDayEntity entity = new ExternalDayEntity();
        entity.component = component;
        entity.day = day;
        return entity;
    }

    public void add(long okDelta, long degradedDelta, long downDelta) {
        this.ok += okDelta;
        this.degraded += degradedDelta;
        this.down += downDelta;
    }
}
