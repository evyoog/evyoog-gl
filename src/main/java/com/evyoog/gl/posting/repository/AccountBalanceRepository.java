package com.evyoog.gl.posting.repository;

import com.evyoog.gl.posting.domain.AccountBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    // account_combination is JSONB — matching the exact combination is done in
    // the service layer since comparing JSONB map equality safely in JPQL is
    // brittle. Per account+period, the candidate list here is small.
    List<AccountBalance> findByLedgerIdAndLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
            UUID ledgerId, UUID legalEntityId, UUID accountingPeriodId, UUID naturalAccountId);

    List<AccountBalance> findByLegalEntityIdAndAccountingPeriodId(UUID legalEntityId, UUID accountingPeriodId);

    boolean existsByLegalEntityIdAndAccountingPeriodId(UUID legalEntityId, UUID accountingPeriodId);

    // Multiple rows are possible when the same natural account appears under
    // different dimension combinations — the caller sums beginningBalance across them.
    List<AccountBalance> findByLegalEntityIdAndAccountingPeriodIdAndNaturalAccountId(
            UUID legalEntityId, UUID accountingPeriodId, UUID naturalAccountId);

    // GL-26 — segment filter via JSONB containment (@>), backed by the GIN
    // index on account_combination. `filter` is a JSON object string, e.g.
    // {"COST_CENTRE":"CC-MFG"} — every JSONB object contains '{}', so an
    // empty filter is equivalent to no filtering.
    @Query(value = """
            SELECT * FROM gl.account_balance
            WHERE legal_entity_id = :legalEntityId
            AND accounting_period_id = :periodId
            AND account_combination @> CAST(:filter AS jsonb)
            """, nativeQuery = true)
    List<AccountBalance> findByLegalEntityIdAndAccountingPeriodIdAndCombinationFilter(
            @Param("legalEntityId") UUID legalEntityId,
            @Param("periodId") UUID periodId,
            @Param("filter") String filter);

    // GL-26 — P&L by Segment pivot: Revenue/Expense balances grouped by
    // Natural Account and by the requested segment's value within
    // account_combination (e.g. "COST_CENTRE" -> "CC-MFG"). The segment
    // extraction is done once in a derived table — repeating
    // `account_combination ->> :segmentType` in both SELECT and GROUP BY
    // binds the named parameter to two separate JDBC placeholders, and
    // Postgres then treats them as distinct expressions and rejects the
    // GROUP BY, even though both hold the same value.
    @Query(value = """
            SELECT natural_account_code AS "naturalAccountCode",
                   natural_account_name AS "naturalAccountName",
                   account_qualifier AS "accountQualifier",
                   segment_value AS "segmentValue",
                   COALESCE(SUM(ptd_dr), 0) AS "ptdDr",
                   COALESCE(SUM(ptd_cr), 0) AS "ptdCr"
            FROM (
                SELECT dv.code AS natural_account_code,
                       dv.name AS natural_account_name,
                       dv.account_qualifier AS account_qualifier,
                       ab.account_combination ->> :segmentType AS segment_value,
                       ab.period_to_date_dr AS ptd_dr,
                       ab.period_to_date_cr AS ptd_cr
                FROM gl.account_balance ab
                JOIN gl.dimension_value dv ON dv.id = ab.natural_account_value_id
                WHERE ab.legal_entity_id = :legalEntityId
                AND ab.accounting_period_id = :periodId
                AND dv.account_qualifier IN ('REVENUE', 'EXPENSE')
            ) segment_balances
            GROUP BY natural_account_code, natural_account_name, account_qualifier, segment_value
            ORDER BY account_qualifier, natural_account_code
            """, nativeQuery = true)
    List<PLSegmentPivotRow> findPLBySegmentPivot(
            @Param("legalEntityId") UUID legalEntityId,
            @Param("periodId") UUID periodId,
            @Param("segmentType") String segmentType);
}
