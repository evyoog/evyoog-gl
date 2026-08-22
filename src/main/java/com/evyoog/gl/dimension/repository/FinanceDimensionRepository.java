package com.evyoog.gl.dimension.repository;

import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceDimensionRepository extends JpaRepository<FinanceDimension, UUID> {

    boolean existsByLedgerIdAndCode(UUID ledgerId, String code);

    long countByLedgerIdAndIsActiveTrue(UUID ledgerId);

    boolean existsByLedgerIdAndDimensionTypeAndIsActiveTrue(UUID ledgerId, DimensionType dimensionType);

    List<FinanceDimension> findByLedgerId(UUID ledgerId);

    List<FinanceDimension> findByLedgerIdAndIsActiveTrue(UUID ledgerId);

    List<FinanceDimension> findByLedgerIdAndDimensionType(UUID ledgerId, DimensionType dimensionType);

    Optional<FinanceDimension> findByLedgerIdAndDimensionTypeAndIsActiveTrue(UUID ledgerId, DimensionType dimensionType);

    List<FinanceDimension> findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(UUID coaStructureId);

    List<FinanceDimension> findByCoaStructureId(UUID coaStructureId);

    boolean existsByCoaStructureIdAndCode(UUID coaStructureId, String code);

    List<FinanceDimension> findByCoaStructureIdAndIsBalancingTrueOrderByBalancingSequenceAsc(UUID coaStructureId);

    boolean existsByCoaStructureIdAndIsBalancingTrueAndBalancingSequence(UUID coaStructureId, Integer balancingSequence);

    boolean existsByCoaStructureIdAndIsBalancingTrueAndBalancingSequenceAndIdNot(
            UUID coaStructureId, Integer balancingSequence, UUID id);
}
