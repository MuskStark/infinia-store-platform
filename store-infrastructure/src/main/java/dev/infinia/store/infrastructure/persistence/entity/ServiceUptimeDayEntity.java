package dev.infinia.store.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * One component's aggregated probe outcomes for one UTC day — the bucket that
 * backs the 90-day uptime bars of the service-status page.
 */
@Entity
@Table(name = "service_uptime_day")
@IdClass(ServiceUptimeDayEntity.Key.class)
public class ServiceUptimeDayEntity {

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
            if (!(o instanceof Key other)) {
                return false;
            }
            return component.equals(other.component) && day.equals(other.day);
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
}
