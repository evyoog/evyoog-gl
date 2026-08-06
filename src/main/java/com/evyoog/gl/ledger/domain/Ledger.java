package com.evyoog.gl.ledger.domain;

import com.evyoog.gl.common.domain.AuditableEntity;
import com.evyoog.gl.enterprise.domain.AccountingStandard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "ledger", schema = "gl")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class Ledger extends AuditableEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "finance_mode", nullable = false, length = 20)
    private FinanceMode financeMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_category", nullable = false, length = 20)
    private LedgerCategory ledgerCategory;

    @Column(name = "functional_currency", nullable = false, length = 3)
    private String functionalCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_standard", nullable = false, length = 20)
    private AccountingStandard accountingStandard;

    @Builder.Default
    @Column(name = "allow_dynamic_insert", nullable = false)
    private boolean allowDynamicInsert = true;
}
