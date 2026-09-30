package com.evyoog.gl.periodstatus.repository;

import com.evyoog.gl.periodstatus.domain.PeriodStatus;
import com.evyoog.gl.periodstatus.domain.PeriodStatusEnum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PeriodStatusRepository extends JpaRepository<PeriodStatus, UUID> {

    Optional<PeriodStatus> findByLegalEntityIdAndAccountingPeriodId(UUID legalEntityId, UUID accountingPeriodId);

    boolean existsByLegalEntityIdAndAccountingPeriodId(UUID legalEntityId, UUID accountingPeriodId);

    List<PeriodStatus> findByLegalEntityId(UUID legalEntityId);

    List<PeriodStatus> findByLegalEntityIdAndStatus(UUID legalEntityId, PeriodStatusEnum status);

    long countByLegalEntityIdAndStatus(UUID legalEntityId, PeriodStatusEnum status);

    /** V33 Rule 2 — baseline for the future-period-ahead calculation. */
    Optional<PeriodStatus> findFirstByLegalEntityIdAndStatusOrderByAccountingPeriod_StartDateAsc(
            UUID legalEntityId, PeriodStatusEnum status);

    /** V33 Rule 3b — closing sequence: any earlier period in the same fiscal year still OPEN. */
    @Query("""
            SELECT ps FROM PeriodStatus ps
            WHERE ps.legalEntity.id = :legalEntityId
              AND ps.status = com.evyoog.gl.periodstatus.domain.PeriodStatusEnum.OPEN
              AND ps.accountingPeriod.fiscalYear = :fiscalYear
              AND ps.accountingPeriod.periodNumber < :periodNumber
            """)
    List<PeriodStatus> findOpenPriorPeriods(@Param("legalEntityId") UUID legalEntityId,
                                             @Param("fiscalYear") String fiscalYear,
                                             @Param("periodNumber") Integer periodNumber);
}
