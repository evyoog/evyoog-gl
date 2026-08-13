package com.evyoog.gl.dimension.service;

import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.dto.CreateFinanceDimensionRequest;
import com.evyoog.gl.dimension.dto.FinanceDimensionResponse;
import com.evyoog.gl.dimension.mapper.FinanceDimensionMapper;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.domain.LedgerCategory;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceDimensionServiceTest {

    @Mock
    private FinanceDimensionRepository repository;
    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private DimensionValueRepository dimensionValueRepository;
    @Mock
    private FinanceDimensionMapper mapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private FinanceDimensionService service;

    private Ledger ledgerWithMode(FinanceMode mode) {
        Ledger ledger = Ledger.builder().code("LDG-001").name("Ledger").financeMode(mode)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").build();
        ledger.setId(UUID.randomUUID());
        return ledger;
    }

    private FinanceDimensionResponse responseFor(FinanceDimension entity, long valueCount) {
        return new FinanceDimensionResponse(entity.getId(), entity.getLedger().getId(), entity.getLedger().getName(),
                null, entity.getCode(), entity.getName(), entity.getDescription(), entity.getDimensionType(),
                entity.isRequired(), entity.getDisplayOrder(), entity.isActive(), valueCount,
                Instant.now(), Instant.now());
    }

    @Test
    void createDimension_success_thickLedger() {
        Ledger ledger = ledgerWithMode(FinanceMode.THICK);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "CC", "Cost Centre", null, DimensionType.COST_CENTRE, null, null);
        FinanceDimension entity = new FinanceDimension();
        FinanceDimension saved = FinanceDimension.builder().code("CC").name("Cost Centre")
                .dimensionType(DimensionType.COST_CENTRE).ledger(ledger).build();
        saved.setId(UUID.randomUUID());

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "CC")).thenReturn(false);
        when(repository.countByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(0L);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(mapper.toResponse(saved, 0L)).thenReturn(responseFor(saved, 0L));

        FinanceDimensionResponse result = service.create(request, "prashanth");

        assertThat(result.code()).isEqualTo("CC");
        assertThat(result.dimensionType()).isEqualTo(DimensionType.COST_CENTRE);
    }

    @Test
    void createDimension_whenDuplicateCode_shouldThrow409() {
        Ledger ledger = ledgerWithMode(FinanceMode.THICK);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "CC", "Cost Centre", null, DimensionType.COST_CENTRE, null, null);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "CC")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_DIMENSION_CODE");
    }

    @Test
    void createDimension_thinLedger_thirdDimension_shouldThrow409() {
        Ledger ledger = ledgerWithMode(FinanceMode.THIN);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "LE", "Legal Entity", null, DimensionType.LEGAL_ENTITY, null, null);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "LE")).thenReturn(false);
        when(repository.countByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(2L);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "THIN_DIMENSION_LIMIT");
    }

    @Test
    void createDimension_thinLedger_invalidType_shouldThrow409() {
        Ledger ledger = ledgerWithMode(FinanceMode.THIN);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "CC", "Cost Centre", null, DimensionType.COST_CENTRE, null, null);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "CC")).thenReturn(false);
        when(repository.countByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "THIN_DIMENSION_TYPE_INVALID");
    }

    @Test
    void createDimension_secondLegalEntity_shouldThrow409() {
        Ledger ledger = ledgerWithMode(FinanceMode.THICK);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "LE2", "Legal Entity 2", null, DimensionType.LEGAL_ENTITY, null, null);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "LE2")).thenReturn(false);
        when(repository.countByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(3L);
        when(repository.existsByLedgerIdAndDimensionTypeAndIsActiveTrue(ledger.getId(), DimensionType.LEGAL_ENTITY))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "LEGAL_ENTITY_DIMENSION_EXISTS");
    }

    @Test
    void createDimension_secondNaturalAccount_shouldThrow409() {
        Ledger ledger = ledgerWithMode(FinanceMode.THICK);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "NA2", "Natural Account 2", null, DimensionType.NATURAL_ACCOUNT, null, null);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "NA2")).thenReturn(false);
        when(repository.countByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(3L);
        when(repository.existsByLedgerIdAndDimensionTypeAndIsActiveTrue(ledger.getId(), DimensionType.NATURAL_ACCOUNT))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NATURAL_ACCOUNT_DIMENSION_EXISTS");
    }

    @Test
    void createDimension_maxFifteen_shouldThrow409() {
        Ledger ledger = ledgerWithMode(FinanceMode.THICK);
        CreateFinanceDimensionRequest request = new CreateFinanceDimensionRequest(
                ledger.getId(), "CUSTOM16", "Custom 16", null, DimensionType.CUSTOM, null, null);

        when(ledgerRepository.findById(ledger.getId())).thenReturn(Optional.of(ledger));
        when(repository.existsByLedgerIdAndCode(ledger.getId(), "CUSTOM16")).thenReturn(false);
        when(repository.countByLedgerIdAndIsActiveTrue(ledger.getId())).thenReturn(15L);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "MAX_DIMENSIONS_EXCEEDED");
    }

    @Test
    void deactivateDimension_success() {
        Ledger ledger = ledgerWithMode(FinanceMode.THICK);
        UUID id = UUID.randomUUID();
        FinanceDimension entity = FinanceDimension.builder().code("CC").name("Cost Centre")
                .dimensionType(DimensionType.COST_CENTRE).ledger(ledger).build();
        entity.setId(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(dimensionValueRepository.countByFinanceDimensionIdAndIsActiveTrue(id)).thenReturn(0L);
        when(mapper.toResponse(eq(entity), anyLong())).thenAnswer(inv -> responseFor(entity, 0L));
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
}
