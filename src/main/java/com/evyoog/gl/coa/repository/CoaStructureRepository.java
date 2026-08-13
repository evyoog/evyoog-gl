package com.evyoog.gl.coa.repository;

import com.evyoog.gl.coa.domain.CoaStructure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoaStructureRepository extends JpaRepository<CoaStructure, UUID> {

    List<CoaStructure> findByBusinessGroupIdAndIsActiveTrue(UUID businessGroupId);

    Optional<CoaStructure> findByBusinessGroupIdAndCode(UUID businessGroupId, String code);

    boolean existsByBusinessGroupIdAndCode(UUID businessGroupId, String code);
}
