package com.evyoog.gl.aie.service;

import com.evyoog.gl.aie.domain.InterfaceBatch;
import com.evyoog.gl.aie.domain.InterfaceError;
import com.evyoog.gl.aie.domain.InterfaceLine;
import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieImportResponse;
import com.evyoog.gl.aie.dto.AieLineErrorResponse;
import com.evyoog.gl.aie.dto.AieLineRequest;
import com.evyoog.gl.aie.dto.BatchStatusResponse;
import com.evyoog.gl.aie.dto.ResubmitRequest;
import com.evyoog.gl.aie.repository.BatchAckLogRepository;
import com.evyoog.gl.aie.repository.DeduplicationLogRepository;
import com.evyoog.gl.aie.repository.InterfaceBatchRepository;
import com.evyoog.gl.aie.repository.InterfaceErrorRepository;
import com.evyoog.gl.aie.repository.InterfaceLineRepository;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.FinanceMode;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LegalEntityLedgerRepository;
import com.evyoog.gl.posting.domain.JournalCategory;
import com.evyoog.gl.posting.domain.JournalHeader;
import com.evyoog.gl.posting.domain.JournalSource;
import com.evyoog.gl.posting.dto.PostingRequest;
import com.evyoog.gl.posting.dto.PostingResult;
import com.evyoog.gl.posting.repository.JournalCategoryRepository;
import com.evyoog.gl.posting.repository.JournalHeaderRepository;
import com.evyoog.gl.posting.repository.JournalSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiePipelineServiceTest {

    @Mock private InterfaceBatchRepository batchRepository;
    @Mock private InterfaceLineRepository lineRepository;
    @Mock private InterfaceErrorRepository errorRepository;
    @Mock private DeduplicationLogRepository deduplicationLogRepository;
    @Mock private BatchAckLogRepository batchAckLogRepository;
    @Mock private LegalEntityLedgerRepository legalEntityLedgerRepository;
    @Mock private FinanceDimensionRepository financeDimensionRepository;
    @Mock private DimensionValueRepository dimensionValueRepository;
    @Mock private JournalSourceRepository journalSourceRepository;
    @Mock private JournalCategoryRepository journalCategoryRepository;
    @Mock private JournalHeaderRepository journalHeaderRepository;
    @Mock private PostingIsolationService postingIsolationService;
    @Mock private AuditService auditService;

    private AiePipelineService service;

    private UUID legalEntityId;
    private UUID ledgerId;
    private UUID accountingPeriodId;
    private UUID financeDimensionId;

    @BeforeEach
    void setUp() {
        service = new AiePipelineService(batchRepository, lineRepository, errorRepository,
                deduplicationLogRepository, batchAckLogRepository, legalEntityLedgerRepository,
                financeDimensionRepository, dimensionValueRepository, journalSourceRepository,
                journalCategoryRepository, journalHeaderRepository, postingIsolationService, auditService);

        legalEntityId = UUID.randomUUID();
        ledgerId = UUID.randomUUID();
        accountingPeriodId = UUID.randomUUID();
        financeDimensionId = UUID.randomUUID();

        lenient().when(batchRepository.save(any(InterfaceBatch.class))).thenAnswer(inv -> {
            InterfaceBatch b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(UUID.randomUUID());
            }
            return b;
        });
        lenient().when(lineRepository.saveAll(any())).thenAnswer(inv -> {
            List<InterfaceLine> lines = inv.getArgument(0);
            lines.forEach(l -> {
                if (l.getId() == null) {
                    l.setId(UUID.randomUUID());
                }
            });
            return lines;
        });
        lenient().when(errorRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deduplicationLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(batchAckLogRepository.save(any())).thenAnswer(inv -> {
            var ack = inv.getArgument(0, com.evyoog.gl.aie.domain.BatchAckLog.class);
            ack.setId(UUID.randomUUID());
            return ack;
        });
    }

    private AieImportRequest balancedRequest(String accountCode1, String accountCode2) {
        AieLineRequest line1 = new AieLineRequest(1, accountCode1, Map.of(),
                new BigDecimal("500.00"), null, "Cash", null, null, null, null);
        AieLineRequest line2 = new AieLineRequest(2, accountCode2, Map.of(),
                null, new BigDecimal("500.00"), "Revenue", null, null, null, null);
        return new AieImportRequest("EVT-1", "SAP", legalEntityId, ledgerId, accountingPeriodId,
                "BATCH-REF-1", "Import from SAP", "aie-user", List.of(line1, line2));
    }

    private void stubLedgerAndAccounts(String code1, String code2) {
        Ledger ledger = Ledger.builder().code("LDG").name("Primary").financeMode(FinanceMode.THICK)
                .functionalCurrency("INR").build();
        ledger.setId(ledgerId);
        when(legalEntityLedgerRepository.findPrimaryLedgerByLegalEntityId(legalEntityId))
                .thenReturn(Optional.of(ledger));

        FinanceDimension naDim = FinanceDimension.builder().code("NA").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).build();
        naDim.setId(financeDimensionId);
        when(financeDimensionRepository.findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(naDim));

        DimensionValue account1 = DimensionValue.builder().code(code1).name("Cash").isPostable(true).isSummary(false).build();
        account1.setId(UUID.randomUUID());
        DimensionValue account2 = DimensionValue.builder().code(code2).name("Revenue").isPostable(true).isSummary(false).build();
        account2.setId(UUID.randomUUID());

        lenient().when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(financeDimensionId, code1))
                .thenReturn(Optional.of(account1));
        lenient().when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(financeDimensionId, code2))
                .thenReturn(Optional.of(account2));
    }

    private void stubImportSourceAndCategory() {
        JournalSource source = JournalSource.builder().id(UUID.randomUUID()).code("IMPORT").name("Import")
                .requiresApproval(false).build();
        JournalCategory category = JournalCategory.builder().id(UUID.randomUUID()).code("IMPORT").name("Import").build();
        when(journalSourceRepository.findByCode("IMPORT")).thenReturn(Optional.of(source));
        when(journalCategoryRepository.findByCode("IMPORT")).thenReturn(Optional.of(category));
    }

    private void stubOpeningBalanceSourceAndCategory() {
        JournalSource source = JournalSource.builder().id(UUID.randomUUID()).code("MANUAL").name("Manual")
                .requiresApproval(false).build();
        JournalCategory category = JournalCategory.builder().id(UUID.randomUUID()).code("OPENING").name("Opening Balance").build();
        when(journalSourceRepository.findByCode("MANUAL")).thenReturn(Optional.of(source));
        when(journalCategoryRepository.findByCode("OPENING")).thenReturn(Optional.of(category));
    }

    @Test
    void testIngest_validBatch_postsJournal() {
        stubLedgerAndAccounts("1000", "4000");
        stubImportSourceAndCategory();

        UUID journalHeaderId = UUID.randomUUID();
        when(postingIsolationService.postIsolated(any(PostingRequest.class)))
                .thenReturn(PostingResult.posted(journalHeaderId, "JE-2601-00001", FinanceMode.THICK));

        JournalHeader header = JournalHeader.builder().journalNumber("JE-2601-00001").build();
        header.setId(journalHeaderId);
        when(journalHeaderRepository.findById(journalHeaderId)).thenReturn(Optional.of(header));

        AieImportResponse response = service.ingest(balancedRequest("1000", "4000"));

        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.journalHeaderId()).isEqualTo(journalHeaderId);
        assertThat(response.journalNumber()).isEqualTo("JE-2601-00001");
        assertThat(response.errorLines()).isZero();
        assertThat(response.validLines()).isEqualTo(2);
    }

    @Test
    void testIngest_openingBalanceSourceAndCategory_postsJournal() {
        // Opening Balance Import (OpeningBalanceService) calls this overload with
        // journalSourceCode=MANUAL/journalCategoryCode=OPENING instead of the
        // default IMPORT/IMPORT — verifies that path posts successfully end to
        // end through PostingIsolationService, the fix for the
        // UnexpectedRollbackException regression where a posting failure used
        // to mark the whole batch-tracking transaction rollback-only.
        stubLedgerAndAccounts("1100", "3100");
        stubOpeningBalanceSourceAndCategory();

        UUID journalHeaderId = UUID.randomUUID();
        when(postingIsolationService.postIsolated(any(PostingRequest.class)))
                .thenReturn(PostingResult.posted(journalHeaderId, "JE-2601-00099", FinanceMode.THICK));

        JournalHeader header = JournalHeader.builder().journalNumber("JE-2601-00099").build();
        header.setId(journalHeaderId);
        when(journalHeaderRepository.findById(journalHeaderId)).thenReturn(Optional.of(header));

        AieImportRequest request = new AieImportRequest("OB-1", "OPENING_BALANCE", legalEntityId, ledgerId,
                accountingPeriodId, null, "Opening Balance Import", "accountant@orbinox.com",
                balancedRequest("1100", "3100").lines());

        AieImportResponse response = service.ingest(request, "MANUAL", "OPENING");

        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.journalHeaderId()).isEqualTo(journalHeaderId);
        verify(postingIsolationService).postIsolated(any(PostingRequest.class));
    }

    @Test
    void testIngest_postingEngineThrowsEvyoogException_returnsFailedWithoutRethrowing() {
        // PostingEngine.post() (called via PostingIsolationService) can throw for
        // business reasons — e.g. BALANCING_SEGMENT_CROSSED, ACCOUNT_NOT_POSTABLE.
        // The pipeline must catch this and persist a FAILED batch, not let the
        // exception escape ingest() (which previously surfaced as an unrelated
        // UnexpectedRollbackException once PostingEngine ran in the same
        // transaction as this method).
        stubLedgerAndAccounts("1000", "4000");
        stubImportSourceAndCategory();

        when(postingIsolationService.postIsolated(any(PostingRequest.class)))
                .thenThrow(new EvyoogException("BALANCING_SEGMENT_CROSSED", "Journal crosses balancing segment."));

        AieImportResponse response = service.ingest(balancedRequest("1000", "4000"));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errors()).anySatisfy(e -> assertThat(e.errorCode()).isEqualTo("BALANCING_SEGMENT_CROSSED"));
        verify(batchAckLogRepository).save(any());
    }

    @Test
    void testIngest_duplicateEventId_throws409() {
        when(deduplicationLogRepository.existsByEventId("EVT-1")).thenReturn(true);

        assertThatThrownBy(() -> service.ingest(balancedRequest("1000", "4000")))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_EVENT_ID");

        verify(batchRepository, never()).save(any());
    }

    @Test
    void testIngest_unbalancedLines_returnsFailed() {
        AieLineRequest line1 = new AieLineRequest(1, "1000", Map.of(), new BigDecimal("500.00"), null, "Cash",
                null, null, null, null);
        AieLineRequest line2 = new AieLineRequest(2, "4000", Map.of(), null, new BigDecimal("400.00"), "Revenue",
                null, null, null, null);
        AieImportRequest request = new AieImportRequest("EVT-2", "SAP", legalEntityId, ledgerId, accountingPeriodId,
                "REF", "desc", "aie-user", List.of(line1, line2));

        AieImportResponse response = service.ingest(request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errors()).anySatisfy(e -> assertThat(e.errorCode()).isEqualTo("UNBALANCED"));
        verify(postingIsolationService, never()).postIsolated(any());
    }

    @Test
    void testIngest_invalidLineAmount_bothDrAndCr_returnsFailed() {
        AieLineRequest line1 = new AieLineRequest(1, "1000", Map.of(),
                new BigDecimal("500.00"), new BigDecimal("500.00"), "Cash", null, null, null, null);
        AieLineRequest line2 = new AieLineRequest(2, "4000", Map.of(), null, new BigDecimal("500.00"), "Revenue",
                null, null, null, null);
        AieImportRequest request = new AieImportRequest("EVT-3", "SAP", legalEntityId, ledgerId, accountingPeriodId,
                "REF", "desc", "aie-user", List.of(line1, line2));

        AieImportResponse response = service.ingest(request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errors()).anySatisfy(e -> {
            assertThat(e.errorCode()).isEqualTo("INVALID_LINE_AMOUNT");
            assertThat(e.lineNumber()).isEqualTo(1);
        });
    }

    @Test
    void testIngest_invalidLineAmount_neitherDrNorCr_returnsFailed() {
        AieLineRequest line1 = new AieLineRequest(1, "1000", Map.of(), null, null, "Cash", null, null, null, null);
        AieLineRequest line2 = new AieLineRequest(2, "4000", Map.of(), null, new BigDecimal("500.00"), "Revenue",
                null, null, null, null);
        AieImportRequest request = new AieImportRequest("EVT-4", "SAP", legalEntityId, ledgerId, accountingPeriodId,
                "REF", "desc", "aie-user", List.of(line1, line2));

        AieImportResponse response = service.ingest(request);

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errors()).anySatisfy(e -> {
            assertThat(e.errorCode()).isEqualTo("INVALID_LINE_AMOUNT");
            assertThat(e.lineNumber()).isEqualTo(1);
        });
    }

    @Test
    void testResubmit_failedBatch_success() {
        UUID batchId = UUID.randomUUID();
        InterfaceBatch original = InterfaceBatch.builder()
                .eventId("EVT-5").sourceSystem("SAP").legalEntityId(legalEntityId).ledgerId(ledgerId)
                .accountingPeriodId(accountingPeriodId).batchReference("REF").status("FAILED")
                .totalLines(2).createdBy("aie-user").build();
        original.setId(batchId);
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(original));

        InterfaceLine l1 = InterfaceLine.builder().batch(original).lineNumber(1).accountCode("1000")
                .accountCombination(Map.of()).debitAmount(new BigDecimal("500.00")).lineStatus("PENDING").build();
        InterfaceLine l2 = InterfaceLine.builder().batch(original).lineNumber(2).accountCode("4000")
                .accountCombination(Map.of()).creditAmount(new BigDecimal("500.00")).lineStatus("PENDING").build();
        when(lineRepository.findByBatchIdOrderByLineNumberAsc(batchId)).thenReturn(List.of(l1, l2));

        stubLedgerAndAccounts("1000", "4000");
        stubImportSourceAndCategory();

        UUID journalHeaderId = UUID.randomUUID();
        when(postingIsolationService.postIsolated(any(PostingRequest.class)))
                .thenReturn(PostingResult.posted(journalHeaderId, "JE-2601-00002", FinanceMode.THICK));
        JournalHeader header = JournalHeader.builder().journalNumber("JE-2601-00002").build();
        header.setId(journalHeaderId);
        when(journalHeaderRepository.findById(journalHeaderId)).thenReturn(Optional.of(header));

        AieImportResponse response = service.resubmit(batchId, new ResubmitRequest("resubmitter1"));

        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.journalHeaderId()).isEqualTo(journalHeaderId);
    }

    @Test
    void testResubmit_postedBatch_throws409() {
        UUID batchId = UUID.randomUUID();
        InterfaceBatch original = InterfaceBatch.builder()
                .eventId("EVT-6").sourceSystem("SAP").legalEntityId(legalEntityId).status("POSTED")
                .totalLines(2).createdBy("aie-user").build();
        original.setId(batchId);
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.resubmit(batchId, new ResubmitRequest("resubmitter1")))
                .isInstanceOf(EvyoogException.class)
                .hasFieldOrPropertyWithValue("code", "BATCH_NOT_FAILED");
    }

    @Test
    void testGetBatchStatus_returnsCorrectStatus() {
        UUID batchId = UUID.randomUUID();
        InterfaceBatch batch = InterfaceBatch.builder()
                .eventId("EVT-7").sourceSystem("SAP").legalEntityId(legalEntityId).status("POSTED")
                .totalLines(2).validLines(2).errorLines(0).createdBy("aie-user").build();
        batch.setId(batchId);
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        BatchStatusResponse response = service.getBatchStatus(batchId);

        assertThat(response.batchId()).isEqualTo(batchId);
        assertThat(response.status()).isEqualTo("POSTED");
        assertThat(response.totalLines()).isEqualTo(2);
    }

    @Test
    void testGetErrors_returnsLineErrors() {
        UUID batchId = UUID.randomUUID();
        InterfaceBatch batch = InterfaceBatch.builder()
                .eventId("EVT-8").sourceSystem("SAP").legalEntityId(legalEntityId).status("FAILED")
                .totalLines(2).createdBy("aie-user").build();
        batch.setId(batchId);
        when(batchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        InterfaceError error = InterfaceError.builder().batch(batch).lineNumber(1).errorCode("UNBALANCED")
                .errorMessage("Batch does not balance.").errorStage("VALIDATE").build();
        when(errorRepository.findByBatchId(batchId)).thenReturn(List.of(error));

        List<AieLineErrorResponse> errors = service.getErrors(batchId);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).errorCode()).isEqualTo("UNBALANCED");
    }
}
