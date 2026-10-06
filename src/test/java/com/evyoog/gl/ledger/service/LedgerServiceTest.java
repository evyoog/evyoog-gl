package com.evyoog.gl.ledger.service;

import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.enterprise.domain.AccountingStandard;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.domain.LedgerCategory;
import com.evyoog.gl.ledger.dto.CreateLedgerRequest;
import com.evyoog.gl.ledger.dto.LedgerResponse;
import com.evyoog.gl.ledger.dto.ReplaceLedgerRequest;
import com.evyoog.gl.ledger.dto.UpdateFinanceModeRequest;
import com.evyoog.gl.ledger.mapper.LedgerMapper;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerRepository repository;
    @Mock
    private LedgerMapper mapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private LedgerService service;

    private LedgerResponse responseFor(Ledger ledger) {
        return new LedgerResponse(ledger.getId(), ledger.getCode(), ledger.getName(), ledger.getDescription(),
                ledger.getFinanceMode(), ledger.getLedgerCategory(), ledger.getFunctionalCurrency(),
                ledger.getAccountingStandard(), ledger.isAllowDynamicInsert(), null, ledger.isActive(),
                Instant.now(), Instant.now());
    }

    @Test
    void create_success() {
        CreateLedgerRequest request = new CreateLedgerRequest("LDG-001", "Primary Ledger", null, FinanceMode.THICK, null, null, null);
        Ledger entity = new Ledger();
        Ledger saved = Ledger.builder().code("LDG-001").name("Primary Ledger").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").accountingStandard(AccountingStandard.IND_AS).build();
        saved.setId(UUID.randomUUID());
        LedgerResponse response = responseFor(saved);

        when(repository.existsByCode("LDG-001")).thenReturn(false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(response);

        LedgerResponse result = service.create(request, "prashanth");

        assertThat(result.code()).isEqualTo("LDG-001");
        assertThat(result.financeMode()).isEqualTo(FinanceMode.THICK);
    }

    @Test
    void create_whenDuplicateCode_shouldThrow409() {
        CreateLedgerRequest request = new CreateLedgerRequest("LDG-001", "Primary Ledger", null, FinanceMode.THICK, null, null, null);
        when(repository.existsByCode("LDG-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_CODE");
    }

    @Test
    void upgradeFinanceMode_thinToThick_shouldSucceed() {
        UUID id = UUID.randomUUID();
        Ledger entity = Ledger.builder().code("LDG-001").name("Ledger").financeMode(FinanceMode.THIN)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").accountingStandard(AccountingStandard.IND_AS).build();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(any())).thenAnswer(inv -> responseFor(inv.getArgument(0)));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        LedgerResponse result = service.upgradeFinanceMode(id, new UpdateFinanceModeRequest(FinanceMode.THICK), "prashanth");

        assertThat(result.financeMode()).isEqualTo(FinanceMode.THICK);
    }

    @Test
    void upgradeFinanceMode_thickToThin_shouldThrow409() {
        UUID id = UUID.randomUUID();
        Ledger entity = Ledger.builder().code("LDG-001").name("Ledger").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").accountingStandard(AccountingStandard.IND_AS).build();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(entity)).thenReturn(responseFor(entity));

        assertThatThrownBy(() -> service.upgradeFinanceMode(id, new UpdateFinanceModeRequest(FinanceMode.THIN), "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "FINANCE_MODE_DOWNGRADE");
    }

    @Test
    void upgradeFinanceMode_eventOnlyToThick_shouldSucceed() {
        UUID id = UUID.randomUUID();
        Ledger entity = Ledger.builder().code("LDG-001").name("Ledger").financeMode(FinanceMode.EVENT_ONLY)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").accountingStandard(AccountingStandard.IND_AS).build();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(any())).thenAnswer(inv -> responseFor(inv.getArgument(0)));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        LedgerResponse result = service.upgradeFinanceMode(id, new UpdateFinanceModeRequest(FinanceMode.THICK), "prashanth");

        assertThat(result.financeMode()).isEqualTo(FinanceMode.THICK);
    }

    @Test
    void deactivate_success() {
        UUID id = UUID.randomUUID();
        Ledger entity = Ledger.builder().code("LDG-001").name("Ledger").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").accountingStandard(AccountingStandard.IND_AS).build();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(any())).thenAnswer(inv -> responseFor(inv.getArgument(0)));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        service.deactivate(id, "prashanth");

        assertThat(entity.isActive()).isFalse();
    }

    @Test
    void getById_whenMissing_shouldThrowResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateLedger_updatesNameAndDescriptionOnly_andAudits() {
        UUID id = UUID.randomUUID();
        Ledger entity = Ledger.builder().code("LDG-001").name("Old Name").description("old").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").accountingStandard(AccountingStandard.IND_AS).build();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(any())).thenAnswer(inv -> responseFor(inv.getArgument(0)));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        LedgerResponse result = service.updateLedger(id, new ReplaceLedgerRequest("  New Name ", "new desc"), "prashanth");

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.description()).isEqualTo("new desc");
        assertThat(result.code()).isEqualTo("LDG-001");
        assertThat(result.financeMode()).isEqualTo(FinanceMode.THICK);
        assertThat(result.functionalCurrency()).isEqualTo("INR");
        assertThat(entity.getUpdatedBy()).isEqualTo("prashanth");
        verify(auditService).log(eq(AuditAction.UPDATE), eq("ledger"), eq(id), any(), any(), eq("prashanth"));
    }

    @Test
    void updateLedger_nullDescription_clearsIt() {
        UUID id = UUID.randomUUID();
        Ledger entity = Ledger.builder().code("LDG-001").name("Ledger").description("old").financeMode(FinanceMode.THICK).build();
        entity.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(any())).thenAnswer(inv -> responseFor(inv.getArgument(0)));
        when(repository.saveAndFlush(entity)).thenReturn(entity);

        service.updateLedger(id, new ReplaceLedgerRequest("Ledger", null), "prashanth");

        assertThat(entity.getDescription()).isNull();
    }

    @Test
    void updateLedger_blankName_shouldThrow400() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.updateLedger(id, new ReplaceLedgerRequest("   ", null), "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NAME_REQUIRED");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void updateLedger_whenMissing_shouldThrowResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLedger(id, new ReplaceLedgerRequest("Ledger", null), "prashanth"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
