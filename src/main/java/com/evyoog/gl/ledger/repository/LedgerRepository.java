package com.evyoog.gl.ledger.repository;

import com.evyoog.gl.ledger.domain.Ledger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LedgerRepository extends JpaRepository<Ledger, UUID> {

    boolean existsByCode(String code);

    long countByCoaStructureIdAndIsActiveTrue(UUID coaStructureId);

    List<Ledger> findByCoaStructureIdAndIsActiveTrue(UUID coaStructureId);

    @Query("""
            select distinct lel.ledger from LegalEntityLedger lel
            where lel.legalEntity.businessGroup.id = :businessGroupId
            and lel.isActive = true
            """)
    List<Ledger> findByBusinessGroupId(@Param("businessGroupId") UUID businessGroupId);
}
