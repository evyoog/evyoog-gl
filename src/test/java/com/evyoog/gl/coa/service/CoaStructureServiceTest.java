package com.evyoog.gl.coa.service;

import com.evyoog.gl.coa.domain.CoaStructure;
import com.evyoog.gl.coa.dto.CoaSegmentSummary;
import com.evyoog.gl.coa.dto.CoaStructureResponse;
import com.evyoog.gl.coa.dto.CreateCoaSegmentRequest;
import com.evyoog.gl.coa.dto.CreateCoaStructureRequest;
import com.evyoog.gl.coa.mapper.CoaStructureMapper;
import com.evyoog.gl.coa.repository.CoaStructureRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.DuplicateResourceException;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.enterprise.domain.BusinessGroup;
import com.evyoog.gl.enterprise.repository.BusinessGroupRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.domain.LedgerCategory;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoaStructureServiceTest {

    @Mock
    private CoaStructureRepository repository;
    @Mock
    private BusinessGroupRepository businessGroupRepository;
    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private FinanceDimensionRepository financeDimensionRepository;
    @Mock
    private DimensionValueRepository dimensionValueRepository;
    @Mock
    private CoaStructureMapper mapper;
    @Mock
    private AuditService auditService;

    private CoaStructureService service;

    private UUID businessGroupId;
    private BusinessGroup businessGroup;

    @BeforeEach
    void setUp() {
        service = new CoaStructureService(repository, businessGroupRepository, ledgerRepository,
                financeDimensionRepository, dimensionValueRepository, mapper, auditService);

        businessGroupId = UUID.randomUUID();
        businessGroup = BusinessGroup.builder().build();
        businessGroup.setId(businessGroupId);

        lenient().when(dimensionValueRepository.countByFinanceDimensionIdAndIsActiveTrue(any())).thenReturn(0L);
        lenient().when(financeDimensionRepository.findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(any()))
                .thenReturn(List.of());
        lenient().when(ledgerRepository.countByCoaStructureIdAndIsActiveTrue(any())).thenReturn(0L);
        lenient().when(mapper.toSegmentSummary(any(), anyLong())).thenReturn(
                new CoaSegmentSummary(UUID.randomUUID(), "SEG", "Segment", DimensionType.COST_CENTRE, 1, false, 0L));
    }

    private CreateCoaSegmentRequest segmentRequest(String code, DimensionType type, int number, boolean required) {
        return new CreateCoaSegmentRequest(code, code + " Name", type, number, required);
    }

    @Test
    void createCoaStructure_withSegments_createsFinanceDimensions() {
        CreateCoaStructureRequest request = new CreateCoaStructureRequest(businessGroupId, "STD-IND-MFG",
                "Standard India Manufacturing COA", "desc", null,
                List.of(segmentRequest("NAT-ACCT", DimensionType.NATURAL_ACCOUNT, 1, true),
                        segmentRequest("COST-CTR", DimensionType.COST_CENTRE, 2, true)));

        when(businessGroupRepository.findById(businessGroupId)).thenReturn(Optional.of(businessGroup));
        when(repository.existsByBusinessGroupIdAndCode(businessGroupId, "STD-IND-MFG")).thenReturn(false);

        CoaStructure saved = CoaStructure.builder().businessGroup(businessGroup).code("STD-IND-MFG")
                .name("Standard India Manufacturing COA").separator(".").build();
        saved.setId(UUID.randomUUID());
        when(repository.saveAndFlush(any(CoaStructure.class))).thenReturn(saved);

        when(mapper.toSegmentEntity(any(CreateCoaSegmentRequest.class))).thenAnswer(invocation -> {
            CreateCoaSegmentRequest req = invocation.getArgument(0);
            return FinanceDimension.builder().code(req.code()).name(req.name())
                    .dimensionType(req.dimensionType()).build();
        });
        when(financeDimensionRepository.saveAndFlush(any(FinanceDimension.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CoaStructureResponse response = service.create(request, "tester");

        assertThat(response.code()).isEqualTo("STD-IND-MFG");
        assertThat(response.separator()).isEqualTo(".");
        verify(financeDimensionRepository, times(2)).saveAndFlush(any(FinanceDimension.class));
        verify(auditService).log(any(), eq("coa_structure"), eq(saved.getId()), any(), any(), eq("tester"));
    }

    @Test
    void createCoaStructure_duplicateCodeInBusinessGroup_throwsDuplicateResourceException() {
        CreateCoaStructureRequest request = new CreateCoaStructureRequest(businessGroupId, "STD-IND-MFG",
                "Name", null, null, List.of(segmentRequest("NAT-ACCT", DimensionType.NATURAL_ACCOUNT, 1, true)));

        when(businessGroupRepository.findById(businessGroupId)).thenReturn(Optional.of(businessGroup));
        when(repository.existsByBusinessGroupIdAndCode(businessGroupId, "STD-IND-MFG")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request, "tester"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_COA_STRUCTURE_CODE");
    }

    @Test
    void createCoaStructure_duplicateSegmentCodeInRequest_throwsEvyoogException() {
        CreateCoaStructureRequest request = new CreateCoaStructureRequest(businessGroupId, "STD-IND-MFG",
                "Name", null, null, List.of(
                        segmentRequest("NAT-ACCT", DimensionType.NATURAL_ACCOUNT, 1, true),
                        segmentRequest("NAT-ACCT", DimensionType.COST_CENTRE, 2, true)));

        when(businessGroupRepository.findById(businessGroupId)).thenReturn(Optional.of(businessGroup));
        when(repository.existsByBusinessGroupIdAndCode(businessGroupId, "STD-IND-MFG")).thenReturn(false);

        assertThatThrownBy(() -> service.create(request, "tester"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_SEGMENT_CODE");
    }

    @Test
    void createCoaStructure_businessGroupNotFound_throwsResourceNotFoundException() {
        CreateCoaStructureRequest request = new CreateCoaStructureRequest(businessGroupId, "STD-IND-MFG",
                "Name", null, null, List.of(segmentRequest("NAT-ACCT", DimensionType.NATURAL_ACCOUNT, 1, true)));

        when(businessGroupRepository.findById(businessGroupId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request, "tester"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assignCoaStructureToLedger_updatesLedgerAndDimensions() {
        UUID coaStructureId = UUID.randomUUID();
        CoaStructure structure = CoaStructure.builder().businessGroup(businessGroup).code("STD-IND-MFG")
                .name("Standard").separator(".").build();
        structure.setId(coaStructureId);

        UUID ledgerId = UUID.randomUUID();
        Ledger ledger = Ledger.builder().code("PRIM-01").name("Primary Ledger").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").build();
        ledger.setId(ledgerId);

        FinanceDimension segment = FinanceDimension.builder().code("NAT-ACCT").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).coaStructure(structure).build();
        segment.setId(UUID.randomUUID());

        when(repository.findById(coaStructureId)).thenReturn(Optional.of(structure));
        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));
        when(ledgerRepository.saveAndFlush(any(Ledger.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(financeDimensionRepository.findByCoaStructureId(coaStructureId)).thenReturn(List.of(segment));

        CoaStructureResponse response = service.assignToLedger(coaStructureId, ledgerId, "tester");

        assertThat(response.id()).isEqualTo(coaStructureId);
        assertThat(ledger.getCoaStructure()).isEqualTo(structure);
        assertThat(segment.getLedger()).isEqualTo(ledger);
        verify(financeDimensionRepository).saveAll(List.of(segment));
    }

    @Test
    void getCoaStructureByLedger_returnsCorrectStructure() {
        UUID ledgerId = UUID.randomUUID();
        UUID coaStructureId = UUID.randomUUID();
        CoaStructure structure = CoaStructure.builder().businessGroup(businessGroup).code("STD-IND-MFG")
                .name("Standard").separator(".").build();
        structure.setId(coaStructureId);

        Ledger ledger = Ledger.builder().code("PRIM-01").name("Primary Ledger").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").coaStructure(structure).build();
        ledger.setId(ledgerId);

        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));

        CoaStructureResponse response = service.getByLedgerId(ledgerId);

        assertThat(response.id()).isEqualTo(coaStructureId);
        assertThat(response.code()).isEqualTo("STD-IND-MFG");
    }

    @Test
    void getCoaStructureByLedger_noAssignment_throwsNoCoaStructureAssigned() {
        UUID ledgerId = UUID.randomUUID();
        Ledger ledger = Ledger.builder().code("PRIM-01").name("Primary Ledger").financeMode(FinanceMode.THICK)
                .ledgerCategory(LedgerCategory.PRIMARY).functionalCurrency("INR").build();
        ledger.setId(ledgerId);

        when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));

        assertThatThrownBy(() -> service.getByLedgerId(ledgerId))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "NO_COA_STRUCTURE_ASSIGNED");
    }

    @Test
    void getCombinationFormat_returnsCorrectFormat() {
        UUID coaStructureId = UUID.randomUUID();
        CoaStructure structure = CoaStructure.builder().businessGroup(businessGroup).code("STD-IND-MFG")
                .name("Standard").separator(".").build();
        structure.setId(coaStructureId);

        FinanceDimension natAcct = FinanceDimension.builder().code("NAT-ACCT").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).displayOrder(1).build();
        FinanceDimension costCentre = FinanceDimension.builder().code("COST-CTR").name("Cost Centre")
                .dimensionType(DimensionType.COST_CENTRE).displayOrder(2).build();

        when(repository.findById(coaStructureId)).thenReturn(Optional.of(structure));
        when(financeDimensionRepository.findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(coaStructureId))
                .thenReturn(List.of(natAcct, costCentre));

        String format = service.getCombinationFormat(coaStructureId);

        assertThat(format).isEqualTo("[NAT-ACCT].[COST-CTR]");
    }

    @Test
    void removeSegment_notBelongingToStructure_throwsSegmentNotInStructure() {
        UUID coaStructureId = UUID.randomUUID();
        CoaStructure structure = CoaStructure.builder().businessGroup(businessGroup).code("STD-IND-MFG")
                .name("Standard").separator(".").build();
        structure.setId(coaStructureId);

        CoaStructure otherStructure = CoaStructure.builder().businessGroup(businessGroup).code("OTHER")
                .name("Other").separator(".").build();
        otherStructure.setId(UUID.randomUUID());

        FinanceDimension segment = FinanceDimension.builder().code("NAT-ACCT").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).coaStructure(otherStructure).build();
        UUID segmentId = UUID.randomUUID();
        segment.setId(segmentId);

        when(repository.findById(coaStructureId)).thenReturn(Optional.of(structure));
        when(financeDimensionRepository.findById(segmentId)).thenReturn(Optional.of(segment));

        assertThatThrownBy(() -> service.removeSegment(coaStructureId, segmentId, "tester"))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "SEGMENT_NOT_IN_STRUCTURE");
    }

    @Test
    void addSegment_duplicateCode_throwsDuplicateResourceException() {
        UUID coaStructureId = UUID.randomUUID();
        CoaStructure structure = CoaStructure.builder().businessGroup(businessGroup).code("STD-IND-MFG")
                .name("Standard").separator(".").build();
        structure.setId(coaStructureId);

        when(repository.findById(coaStructureId)).thenReturn(Optional.of(structure));
        when(financeDimensionRepository.existsByCoaStructureIdAndCode(coaStructureId, "PRODUCT")).thenReturn(true);

        assertThatThrownBy(() -> service.addSegment(coaStructureId,
                segmentRequest("PRODUCT", DimensionType.PRODUCT, 3, false), "tester"))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_SEGMENT_CODE");
    }
}
