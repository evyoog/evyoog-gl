package com.evyoog.gl.periodmanagement.domain;

import com.evyoog.gl.enterprise.domain.LegalEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per Legal Entity. Does NOT extend AuditableEntity — this table has
 * no is_active column (there is nothing to soft-delete; a Legal Entity always
 * has exactly one config row), same reasoning as GL-09's AccountingPeriod and
 * GL-10's PeriodStatus.
 */
@Entity
@Table(name = "legal_entity_period_config", schema = "gl")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LegalEntityPeriodConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "legal_entity_id", nullable = false, unique = true)
    private LegalEntity legalEntity;

    @Builder.Default
    @Column(name = "max_open_periods", nullable = false)
    private Integer maxOpenPeriods = 2;

    @Builder.Default
    @Column(name = "future_period_limit", nullable = false)
    private Integer futurePeriodLimit = 1;

    @Builder.Default
    @Column(name = "adjustment_period_enabled", nullable = false)
    private Boolean adjustmentPeriodEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
