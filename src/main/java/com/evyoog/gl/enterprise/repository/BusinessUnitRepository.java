package com.evyoog.gl.enterprise.repository;

import com.evyoog.gl.enterprise.domain.BusinessUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessUnitRepository extends JpaRepository<BusinessUnit, UUID> {

    List<BusinessUnit> findByLegalEntityId(UUID legalEntityId);

    boolean existsByLegalEntityIdAndCode(UUID legalEntityId, String code);

    Optional<BusinessUnit> findByGstin(String gstin);

    boolean existsByGstin(String gstin);

    boolean existsByGstinAndLegalEntityIdNot(String gstin, UUID legalEntityId);

    Optional<BusinessUnit> findFirstByLegalEntityIdAndIsActiveTrue(UUID legalEntityId);
}
