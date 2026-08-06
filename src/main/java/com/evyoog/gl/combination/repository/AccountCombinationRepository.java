package com.evyoog.gl.combination.repository;

import com.evyoog.gl.combination.domain.AccountCombination;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountCombinationRepository extends JpaRepository<AccountCombination, UUID> {

    // combination is JSONB — exact-match lookup is done via native jsonb
    // equality rather than JPQL, matching the AccountBalanceRepository
    // convention (JPQL Map equality against a jsonb-mapped attribute is
    // brittle). `combination` is a JSON object string, e.g.
    // {"NATURAL_ACCOUNT":"5100","COST_CENTRE":"CC-MFG"}.
    @Query(value = """
            SELECT * FROM gl.account_combination
            WHERE ledger_id = :ledgerId
            AND legal_entity_id = :legalEntityId
            AND combination = CAST(:combination AS jsonb)
            """, nativeQuery = true)
    Optional<AccountCombination> findByLedgerIdAndLegalEntityIdAndCombination(
            @Param("ledgerId") UUID ledgerId,
            @Param("legalEntityId") UUID legalEntityId,
            @Param("combination") String combination);

    // GL-* segment filter via JSONB containment (@>), backed by the GIN
    // index on combination. `filter` is a JSON object string — every JSONB
    // object contains '{}', so an empty filter is equivalent to no filtering.
    @Query(value = """
            SELECT * FROM gl.account_combination
            WHERE ledger_id = :ledgerId
            AND legal_entity_id = :legalEntityId
            AND (CAST(:isActive AS boolean) IS NULL OR is_active = CAST(:isActive AS boolean))
            AND combination @> CAST(:filter AS jsonb)
            ORDER BY combination_code
            """, nativeQuery = true)
    List<AccountCombination> searchByFilter(
            @Param("ledgerId") UUID ledgerId,
            @Param("legalEntityId") UUID legalEntityId,
            @Param("filter") String filter,
            @Param("isActive") Boolean isActive);
}
