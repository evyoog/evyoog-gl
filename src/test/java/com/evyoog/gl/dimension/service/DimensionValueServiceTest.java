package com.evyoog.gl.dimension.service;

import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.common.exception.ValidationException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.dimension.dto.CreateDimensionValueRequest;
import com.evyoog.gl.dimension.dto.DimensionValueResponse;
import com.evyoog.gl.dimension.dto.UpdateDimensionValueRequest;
import com.evyoog.gl.dimension.mapper.DimensionValueMapper;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.enterprise.domain.BusinessGroup;
import com.evyoog.gl.enterprise.domain.LegalEntity;
import com.evyoog.gl.enterprise.repository.LegalEntityRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.domain.LegalEntityLedger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DimensionValueServiceTest {

    @Mock
    private DimensionValueRepository repository;
    @Mock
    private FinanceDimensionRepository financeDimensionRepository;
    @Mock
    private LegalEntityRepository legalEntityRepository;
    @Mock
    private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock
    private DimensionValueMapper mapper;
    @Mock
    private AuditService auditService;
    @InjectMocks
    private DimensionValueService service;

    private FinanceDimension financeDimension(DimensionType type) {
        Ledger ledger = Ledger.builder().code("LDG").name("Ledger").build();
        ledger.setId(UUID.randomUUID());
        FinanceDimension fd = FinanceDimension.builder().code("FD").name("Dimension").dimensionType(type)
                .ledger(ledger).build();
        fd.setId(UUID.randomUUID());
        return fd;
    }

    private DimensionValueResponse responseFor(DimensionValue entity) {
        return new DimensionValueResponse(entity.getId(), entity.getFinanceDimension().getId(), "FD", "Dimension",
                entity.getFinanceDimension().getDimensionType(), entity.getCode(), entity.getName(),
                entity.getDescription(), null, null, null, entity.getAccountQualifier(), entity.isSummary(),
                entity.isPostable(), entity.getNormalBalance(), entity.isGstApplicable(), entity.isTdsApplicable(),
                entity.getTdsSection(), entity.getDisplayOrder(), entity.isDefault(), entity.isActive(), null, null,
                null, null, null, entity.getValidFrom(), entity.getValidTo(), entity.isBudgetControlled(),
                entity.getExtendedAttributes(), Instant.now(), Instant.now());
    }

    private CreateDimensionValueRequest basicRequest(UUID financeDimensionId, String code, String name,
                                                       UUID parentValueId, AccountQualifier qualifier) {
        return new CreateDimensionValueRequest(financeDimensionId, code, name, null, parentValueId, qualifier,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void createValue_success() {
        FinanceDimension fd = financeDimension(DimensionType.COST_CENTRE);
        CreateDimensionValueRequest request = basicRequest(fd.getId(), "CC-001", "Cost Centre 1", null, null);
        DimensionValue entity = new DimensionValue();
        DimensionValue saved = DimensionValue.builder().code("CC-001").name("Cost Centre 1").financeDimension(fd).build();
        saved.setId(UUID.randomUUID());

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "CC-001")).thenReturn(false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(entity)).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(responseFor(saved));

        DimensionValueResponse result = service.create(request, "prashanth");

        assertThat(result.code()).isEqualTo("CC-001");
    }

    @Test
    void createValue_duplicateCode_shouldThrow409() {
        FinanceDimension fd = financeDimension(DimensionType.COST_CENTRE);
        CreateDimensionValueRequest request = basicRequest(fd.getId(), "CC-001", "Cost Centre 1", null, null);

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "CC-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_DIMENSION_VALUE_CODE");
    }

    @Test
    void createValue_naturalAccountWithoutQualifier_shouldThrow400() {
        FinanceDimension fd = financeDimension(DimensionType.NATURAL_ACCOUNT);
        CreateDimensionValueRequest request = basicRequest(fd.getId(), "1000", "Cash", null, null);

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "1000")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "ACCOUNT_QUALIFIER_REQUIRED");
    }

    @Test
    void createValue_parentChildQualifierMismatch_shouldThrow409() {
        FinanceDimension fd = financeDimension(DimensionType.NATURAL_ACCOUNT);
        DimensionValue parent = DimensionValue.builder().code("1000").name("Assets").financeDimension(fd)
                .accountQualifier(AccountQualifier.ASSET).build();
        parent.setId(UUID.randomUUID());

        CreateDimensionValueRequest request = basicRequest(fd.getId(), "2000", "Payables", parent.getId(),
                AccountQualifier.LIABILITY);

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "2000")).thenReturn(false);
        when(repository.findById(parent.getId())).thenReturn(Optional.of(parent));

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "QUALIFIER_MISMATCH");
    }

    @Test
    void autoDeriveNormalBalance_asset_returnsDR() {
        FinanceDimension fd = financeDimension(DimensionType.NATURAL_ACCOUNT);
        CreateDimensionValueRequest request = basicRequest(fd.getId(), "1000", "Cash", null, AccountQualifier.ASSET);
        DimensionValue entity = new DimensionValue();

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "1000")).thenReturn(false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(any(DimensionValue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));

        ArgumentCaptor<DimensionValue> captor = ArgumentCaptor.forClass(DimensionValue.class);
        service.create(request, "prashanth");

        org.mockito.Mockito.verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNormalBalance()).isEqualTo(NormalBalance.DR);
    }

    @Test
    void autoDeriveNormalBalance_liability_returnsCR() {
        FinanceDimension fd = financeDimension(DimensionType.NATURAL_ACCOUNT);
        CreateDimensionValueRequest request = basicRequest(fd.getId(), "2000", "Payables", null,
                AccountQualifier.LIABILITY);
        DimensionValue entity = new DimensionValue();

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "2000")).thenReturn(false);
        when(mapper.toEntity(request)).thenReturn(entity);
        when(repository.saveAndFlush(any(DimensionValue.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));

        ArgumentCaptor<DimensionValue> captor = ArgumentCaptor.forClass(DimensionValue.class);
        service.create(request, "prashanth");

        org.mockito.Mockito.verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getNormalBalance()).isEqualTo(NormalBalance.CR);
    }

    @Test
    void createIntercompanyValue_withoutCounterparty_shouldThrow400() {
        FinanceDimension fd = financeDimension(DimensionType.INTERCOMPANY);
        CreateDimensionValueRequest request = basicRequest(fd.getId(), "IC-001", "Intercompany 1", null, null);

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "IC-001")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "IC_COUNTERPARTY_REQUIRED");
    }

    @Test
    void createIntercompanyValue_counterpartyWrongGroup_shouldThrow409() {
        FinanceDimension fd = financeDimension(DimensionType.INTERCOMPANY);

        BusinessGroup ledgerGroup = BusinessGroup.builder().code("BG1").name("Group 1").build();
        ledgerGroup.setId(UUID.randomUUID());
        LegalEntity ownerLe = LegalEntity.builder().code("LE1").name("Owner LE").businessGroup(ledgerGroup).build();
        ownerLe.setId(UUID.randomUUID());
        LegalEntityLedger lel = LegalEntityLedger.builder().legalEntity(ownerLe).ledger(fd.getLedger()).build();

        BusinessGroup otherGroup = BusinessGroup.builder().code("BG2").name("Group 2").build();
        otherGroup.setId(UUID.randomUUID());
        LegalEntity counterparty = LegalEntity.builder().code("LE2").name("Counterparty LE").businessGroup(otherGroup).build();
        counterparty.setId(UUID.randomUUID());

        CreateDimensionValueRequest request = new CreateDimensionValueRequest(fd.getId(), "IC-001", "Intercompany 1",
                null, null, null, null, null, null, null, null, null, null, null, counterparty.getId(), null, null,
                null, null, null, null, null);

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "IC-001")).thenReturn(false);
        when(legalEntityRepository.findById(counterparty.getId())).thenReturn(Optional.of(counterparty));
        when(legalEntityLedgerRepository.findByLedgerId(fd.getLedger().getId())).thenReturn(List.of(lel));

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "IC_COUNTERPARTY_WRONG_GROUP");
    }

    @Test
    void createValue_invalidDateRange_shouldThrow400() {
        FinanceDimension fd = financeDimension(DimensionType.COST_CENTRE);
        CreateDimensionValueRequest request = new CreateDimensionValueRequest(fd.getId(), "CC-001", "Cost Centre 1",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), null, null);

        when(financeDimensionRepository.findById(fd.getId())).thenReturn(Optional.of(fd));
        when(repository.existsByFinanceDimensionIdAndCode(fd.getId(), "CC-001")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request, "prashanth"))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DATE_RANGE");
    }

    @Test
    void deactivateValue_success() {
        FinanceDimension fd = financeDimension(DimensionType.COST_CENTRE);
        UUID id = UUID.randomUUID();
        DimensionValue entity = DimensionValue.builder().code("CC-001").name("Cost Centre 1").financeDimension(fd).build();
        entity.setId(id);

        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));
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
    void testSetDefault_setsDefaultAndClearsPrevious() {
        FinanceDimension fd = financeDimension(DimensionType.PRODUCT);

        DimensionValue previousDefault = DimensionValue.builder().code("SPARES").name("Spares")
                .financeDimension(fd).build();
        previousDefault.setId(UUID.randomUUID());
        previousDefault.setDefault(true);

        DimensionValue target = DimensionValue.builder().code("GATE-VLV").name("Gate Valve")
                .financeDimension(fd).build();
        target.setId(UUID.randomUUID());

        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(repository.findByFinanceDimensionIdAndIsDefaultTrue(fd.getId())).thenReturn(Optional.of(previousDefault));
        when(repository.save(previousDefault)).thenReturn(previousDefault);
        when(repository.saveAndFlush(target)).thenReturn(target);
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));

        DimensionValueResponse result = service.setDefault(target.getId(), "prashanth");

        assertThat(previousDefault.isDefault()).isFalse();
        assertThat(target.isDefault()).isTrue();
        assertThat(result.isDefault()).isTrue();
        org.mockito.Mockito.verify(repository).save(previousDefault);
    }

    @Test
    void testSetDefault_onlyOneDefaultPerDimension() {
        FinanceDimension fd = financeDimension(DimensionType.PRODUCT);
        DimensionValue target = DimensionValue.builder().code("GATE-VLV").name("Gate Valve")
                .financeDimension(fd).build();
        target.setId(UUID.randomUUID());

        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(repository.findByFinanceDimensionIdAndIsDefaultTrue(fd.getId())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(target)).thenReturn(target);
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));

        service.setDefault(target.getId(), "prashanth");

        assertThat(target.isDefault()).isTrue();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any(DimensionValue.class));
    }

    private UpdateDimensionValueRequest parentUpdateRequest(UUID parentValueId) {
        return new UpdateDimensionValueRequest(null, null, parentValueId, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    @Test
    void testUpdateDimensionValue_selfParent_throwsCircularReferenceException() {
        FinanceDimension fd = financeDimension(DimensionType.NATURAL_ACCOUNT);
        DimensionValue a = DimensionValue.builder().code("1000").name("Assets").financeDimension(fd).build();
        a.setId(UUID.randomUUID());

        when(repository.findById(a.getId())).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.update(a.getId(), parentUpdateRequest(a.getId()), "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CIRCULAR_REFERENCE");
    }

    @Test
    void testUpdateDimensionValue_withCircularParent_throwsCircularReferenceException() {
        FinanceDimension fd = financeDimension(DimensionType.NATURAL_ACCOUNT);
        DimensionValue a = DimensionValue.builder().code("1000").name("Assets").financeDimension(fd).build();
        a.setId(UUID.randomUUID());
        DimensionValue b = DimensionValue.builder().code("1100").name("Current Assets").financeDimension(fd)
                .parentValue(a).build();
        b.setId(UUID.randomUUID());
        DimensionValue c = DimensionValue.builder().code("1110").name("Bank").financeDimension(fd)
                .parentValue(b).build();
        c.setId(UUID.randomUUID());

        when(repository.findById(a.getId())).thenReturn(Optional.of(a));
        when(repository.findById(b.getId())).thenReturn(Optional.of(b));
        when(repository.findById(c.getId())).thenReturn(Optional.of(c));

        // Setting C (a's own grandchild) as A's parent would close the loop A -> C -> B -> A.
        assertThatThrownBy(() -> service.update(a.getId(), parentUpdateRequest(c.getId()), "prashanth"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "CIRCULAR_REFERENCE");
    }

    @Test
    void testUpdateDimensionValue_validParent_succeeds() {
        FinanceDimension fd = financeDimension(DimensionType.COST_CENTRE);
        DimensionValue target = DimensionValue.builder().code("CC-SAL").name("Sales").financeDimension(fd).build();
        target.setId(UUID.randomUUID());
        DimensionValue newParent = DimensionValue.builder().code("CC-ROOT").name("All Cost Centres")
                .financeDimension(fd).build();
        newParent.setId(UUID.randomUUID());

        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(repository.findById(newParent.getId())).thenReturn(Optional.of(newParent));
        when(repository.saveAndFlush(target)).thenReturn(target);
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));

        service.update(target.getId(), parentUpdateRequest(newParent.getId()), "prashanth");

        assertThat(target.getParentValue()).isEqualTo(newParent);
    }

    @Test
    void testClearDefault_clearsDefaultFlag() {
        FinanceDimension fd = financeDimension(DimensionType.PRODUCT);
        DimensionValue target = DimensionValue.builder().code("GATE-VLV").name("Gate Valve")
                .financeDimension(fd).build();
        target.setId(UUID.randomUUID());
        target.setDefault(true);

        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(repository.saveAndFlush(target)).thenReturn(target);
        when(mapper.toResponse(any(DimensionValue.class))).thenAnswer(inv -> responseFor(inv.getArgument(0)));

        DimensionValueResponse result = service.clearDefault(target.getId(), "prashanth");

        assertThat(target.isDefault()).isFalse();
        assertThat(result.isDefault()).isFalse();
    }
}
