package com.evyoog.gl.periodmanagement.repository;

import com.evyoog.gl.periodmanagement.domain.LegalEntityPeriodConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LegalEntityPeriodConfigRepository extends JpaRepository<LegalEntityPeriodConfig, UUID> {

    Optional<LegalEntityPeriodConfig> findByLegalEntityId(UUID legalEntityId);
}
