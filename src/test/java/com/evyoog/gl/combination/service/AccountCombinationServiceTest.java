package com.evyoog.gl.combination.service;

import com.evyoog.gl.combination.domain.AccountCombination;
import com.evyoog.gl.combination.dto.AccountCombinationResponse;
import com.evyoog.gl.combination.dto.CreateAccountCombinationRequest;
import com.evyoog.gl.combination.dto.UpdateAccountCombinationRequest;
import com.evyoog.gl.combination.mapper.AccountCombinationMapper;
import com.evyoog.gl.combination.repository.AccountCombinationRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountCombinationServiceTest {

    @Mock
    private AccountCombinationRepository repository;
    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private LegalEntityRepository legalEntityRepository;
    @Mock
    private AccountCombinationMapper mapper;
    @Mock
    private AuditService auditService;

    private AccountCombinationService service;

    private UUID ledgerId;
    private UUID legalEntityId;
    private Ledger ledger;
    private LegalEntity legalEntity;
    private Map<String, String> combination;

    @BeforeEach
    void setUp() {
        service = new AccountCombinationService(repository, ledgerRepository, legalEntityRepository,
                mapper, auditService, new ObjectMapper());

        ledgerId = UUID.randomUUID();
        legalEntityId = UUID.randomUUID();
        ledger = Ledger.builder().id(ledgerId).code("LDG").name("Primary Ledger").build();
        legalEntity = LegalEntity.builder().id(legalEntityId).code("LE1").name("Orbinox Valves").build();
        combination = Map.of("NATURAL_ACCOUNT", "5100", "COST_CENTRE", "CC-MFG");

        lenient().when(mapper.toResponse(any(AccountCombination.class)))
                .thenReturn(responseFor(legalEntityId));
    }

    private AccountCombinationResponse responseFor(UUID legalEntityId) {
        return new AccountCombinationResponse(UUID.randomUUID(), ledgerId, "Primary Ledger", legalEntityId,
                "Orbinox Valves", combination, "5100.CC-MFG", "desc", true, true, Instant.now(),
                Instant.now(), Instant.now(), "tester", "tester");
    }

    @Test
    void validateCombination_existsAndActive_passes() {
        AccountCombination existing = AccountCombination.builder().id(UUID.randomUUID())
                .ledger(ledger).legalEntity(legalEntity).combination(combination)
                .firstUsedAt(Instant.now()).build();
        existing.setActive(true);
        when(repository.findByLedgerIdAndLegalEntityIdAndCombination(eq(ledgerId), eq(legalEntityId), anyString()))
                .thenReturn(Optional.of(existing));

        AccountCombination result = service.validate(ledgerId, legalEntityId, combination, false, "tester");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void validateCombination_existsAndInactive_throws() {
        AccountCombination existing = AccountCombination.builder().id(UUID.randomUUID())
                .ledger(ledger).legalEntity(legalEntity).combination(combination).build();
        existing.setActive(false);
        when(repository.findByLedgerIdAndLegalEntityIdAndCombination(eq(ledgerId), eq(legalEntityId), anyString()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.validate(ledgerId, legalEntityId, combination, true, "tester"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "COMBINATION_INACTIVE");
    }

    @Test
    void validateCombination_notExists_dynamicInsertOn_autoRegisters() {
        when(repository.findByLedgerIdAndLegalEntityIdAndCombination(eq(ledgerId), eq(legalEntityId), anyString()))
                .thenReturn(Optional.empty());
        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
        when(repository.save(any(AccountCombination.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountCombination result = service.validate(ledgerId, legalEntityId, combination, true, "tester");

        assertThat(result.isDynamic()).isTrue();
        assertThat(result.isActive()).isTrue();
        assertThat(result.getCombinationCode()).isEqualTo("5100.CC-MFG");
        assertThat(result.getFirstUsedAt()).isNotNull();
    }

    @Test
    void validateCombination_notExists_dynamicInsertOff_throws() {
        when(repository.findByLedgerIdAndLegalEntityIdAndCombination(eq(ledgerId), eq(legalEntityId), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(ledgerId, legalEntityId, combination, false, "tester"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ACCOUNT_COMBINATION");
    }

    @Test
    void listCombinations_withCostCentreFilter_returnsFiltered() {
        AccountCombination existing = AccountCombination.builder().id(UUID.randomUUID())
                .ledger(ledger).legalEntity(legalEntity).combination(combination).build();
        when(repository.searchByFilter(eq(ledgerId), eq(legalEntityId), anyString(), isNull()))
                .thenReturn(List.of(existing));

        List<AccountCombinationResponse> result = service.list(ledgerId, legalEntityId, "CC-MFG", null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void create_duplicateCombination_throwsConflict() {
        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));
        when(legalEntityRepository.findById(legalEntityId)).thenReturn(Optional.of(legalEntity));
        when(repository.findByLedgerIdAndLegalEntityIdAndCombination(eq(ledgerId), eq(legalEntityId), anyString()))
                .thenReturn(Optional.of(AccountCombination.builder().id(UUID.randomUUID()).build()));

        CreateAccountCombinationRequest request =
                new CreateAccountCombinationRequest(ledgerId, legalEntityId, combination, "desc");

        assertThatThrownBy(() -> service.create(request, "tester"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_COMBINATION");
    }

    @Test
    void deactivate_setsInactive() {
        AccountCombination existing = AccountCombination.builder().id(UUID.randomUUID())
                .ledger(ledger).legalEntity(legalEntity).combination(combination).build();
        existing.setActive(true);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any(AccountCombination.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(existing.getId(), "tester");

        assertThat(existing.isActive()).isFalse();
    }

    @Test
    void update_notFound_throwsResourceNotFound() {
        UUID missingId = UUID.randomUUID();
        when(repository.findById(missingId)).thenReturn(Optional.empty());

        UpdateAccountCombinationRequest request = new UpdateAccountCombinationRequest("new desc", false);

        assertThatThrownBy(() -> service.update(missingId, request, "tester"))
                .isInstanceOf(com.evyoog.gl.common.exception.ResourceNotFoundException.class);
    }
}
