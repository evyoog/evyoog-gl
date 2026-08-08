package com.evyoog.gl.dimension.repository;

import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DimensionValueRepository extends JpaRepository<DimensionValue, UUID> {

    boolean existsByFinanceDimensionIdAndCode(UUID financeDimensionId, String code);

    long countByFinanceDimensionIdAndIsActiveTrue(UUID financeDimensionId);

    List<DimensionValue> findByFinanceDimensionId(UUID financeDimensionId);

    List<DimensionValue> findByFinanceDimensionIdAndParentValueId(UUID financeDimensionId, UUID parentValueId);

    List<DimensionValue> findByFinanceDimensionIdAndIsActiveTrue(UUID financeDimensionId);

    List<DimensionValue> findByFinanceDimensionIdAndIsPostableTrueAndIsActiveTrue(UUID financeDimensionId);

    Optional<DimensionValue> findByFinanceDimensionIdAndCodeAndIsActiveTrue(UUID financeDimensionId, String code);

    Optional<DimensionValue> findByIdAndFinanceDimensionIdAndIsActiveTrue(UUID id, UUID financeDimensionId);

    long countByParentValueIdAndIsActiveTrue(UUID parentValueId);

    Optional<DimensionValue> findByFinanceDimensionIdAndIsDefaultTrue(UUID financeDimensionId);

    List<DimensionValue> findByFinanceDimensionIdAndIsActiveTrueOrderByDisplayOrderAsc(UUID financeDimensionId);

    @Query("""
            select dv from DimensionValue dv
            where dv.financeDimension.ledger.id = :ledgerId
            and dv.code = :code
            """)
    List<DimensionValue> findByLedgerIdAndCode(@Param("ledgerId") UUID ledgerId, @Param("code") String code);

    List<DimensionValue> findByFinanceDimension_Ledger_IdAndAccountQualifierIn(
            UUID ledgerId, List<AccountQualifier> qualifiers);

    @Query("""
            select dv from DimensionValue dv
            where dv.financeDimension.id = :financeDimensionId
            and dv.isActive = true
            and (lower(dv.code) like lower(concat('%', :query, '%'))
                 or lower(dv.name) like lower(concat('%', :query, '%')))
            """)
    List<DimensionValue> search(@Param("financeDimensionId") UUID financeDimensionId, @Param("query") String query);
}
