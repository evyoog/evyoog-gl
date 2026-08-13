package com.evyoog.gl.dimension.domain;

import com.evyoog.gl.coa.domain.CoaStructure;
import com.evyoog.gl.common.domain.AuditableEntity;
import com.evyoog.gl.ledger.domain.Ledger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "finance_dimension", schema = "gl")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class FinanceDimension extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ledger_id")
    private Ledger ledger;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coa_structure_id")
    private CoaStructure coaStructure;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "dimension_type", nullable = false, length = 30)
    private DimensionType dimensionType;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
