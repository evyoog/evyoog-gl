package com.evyoog.gl.gst.domain;

import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.period.domain.AccountingPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "gstr_export_job", schema = "gl")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GstrExportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "legal_entity_id", nullable = false)
    private LegalEntity legalEntity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_period_id", nullable = false)
    private AccountingPeriod accountingPeriod;

    @Column(name = "return_type", nullable = false, length = 10)
    private String returnType;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "export_data", columnDefinition = "jsonb")
    private Map<String, Object> exportData;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
