package com.evyoog.gl.aie.openingbalance.service;

import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieImportResponse;
import com.evyoog.gl.aie.dto.AieLineRequest;
import com.evyoog.gl.aie.service.AiePipelineService;
import com.evyoog.gl.coa.domain.CoaStructure;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalanceImportResponse;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalancePreviewResponse;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpeningBalanceServiceTest {

    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private FinanceDimensionRepository financeDimensionRepository;
    @Mock
    private DimensionValueRepository dimensionValueRepository;
    @Mock
    private AiePipelineService aiePipelineService;

    private OpeningBalanceService service;

    private UUID ledgerId;
    private UUID coaStructureId;
    private FinanceDimension naturalAcctDim;
    private FinanceDimension costCentreDim;

    @BeforeEach
    void setUp() {
        service = new OpeningBalanceService(ledgerRepository, financeDimensionRepository,
                dimensionValueRepository, aiePipelineService);

        ledgerId = UUID.randomUUID();
        coaStructureId = UUID.randomUUID();

        naturalAcctDim = FinanceDimension.builder()
                .id(UUID.randomUUID()).code("NAT-ACCT").name("Natural Account")
                .dimensionType(DimensionType.NATURAL_ACCOUNT).isRequired(true).displayOrder(1).build();
        costCentreDim = FinanceDimension.builder()
                .id(UUID.randomUUID()).code("COST-CTR").name("Cost Centre")
                .dimensionType(DimensionType.COST_CENTRE).isRequired(true).displayOrder(2).build();

        CoaStructure coaStructure = CoaStructure.builder().id(coaStructureId).build();
        Ledger ledger = Ledger.builder().id(ledgerId).coaStructure(coaStructure).build();

        lenient().when(ledgerRepository.findById(ledgerId)).thenReturn(Optional.of(ledger));
        lenient().when(financeDimensionRepository
                        .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT))
                .thenReturn(Optional.of(naturalAcctDim));
        lenient().when(financeDimensionRepository
                        .findByCoaStructureIdAndIsActiveTrueOrderByDisplayOrderAsc(coaStructureId))
                .thenReturn(List.of(naturalAcctDim, costCentreDim));
        lenient().when(dimensionValueRepository
                        .findByFinanceDimensionIdAndCodeAndIsActiveTrue(costCentreDim.getId(), "CC-ADM"))
                .thenReturn(Optional.of(DimensionValue.builder().id(UUID.randomUUID()).code("CC-ADM").name("Admin").build()));
    }

    private DimensionValue account(String code, AccountQualifier qualifier, NormalBalance normalBalance) {
        return DimensionValue.builder()
                .id(UUID.randomUUID())
                .financeDimension(naturalAcctDim)
                .code(code)
                .name(code + " account")
                .accountQualifier(qualifier)
                .normalBalance(normalBalance)
                .build();
    }

    private void stubAccount(String code, AccountQualifier qualifier, NormalBalance normalBalance) {
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(naturalAcctDim.getId(), code))
                .thenReturn(Optional.of(account(code, qualifier, normalBalance)));
    }

    private MockMultipartFile workbook(String[][] rows) throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Opening Balances");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("accountCode");
        header.createCell(1).setCellValue("COST-CTR");
        header.createCell(2).setCellValue("balance");
        header.createCell(3).setCellValue("description");

        for (int r = 0; r < rows.length; r++) {
            Row row = sheet.createRow(r + 1);
            for (int c = 0; c < rows[r].length; c++) {
                String value = rows[r][c];
                if (value == null) {
                    continue;
                }
                if (c == 2) {
                    row.createCell(c).setCellValue(Double.parseDouble(value));
                } else {
                    row.createCell(c).setCellValue(value);
                }
            }
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            workbook.close();
            return new MockMultipartFile("file", "opening_balance.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    // ── template ─────────────────────────────────────────────────────────────

    @Test
    void testGenerateTemplate_hasCorrectColumns() throws Exception {
        byte[] template = service.generateTemplate(ledgerId);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(template))) {
            Sheet sheet = workbook.getSheet("Opening Balances");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("accountCode");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("COST-CTR");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("balance");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("description");
        }
    }

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    void testPreview_validFile_classifiesDrCrCorrectly() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("2100", AccountQualifier.LIABILITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash opening balance"},
                {"2100", "CC-ADM", "500000", "Accounts Payable opening balance"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.errorLines()).isZero();
        assertThat(response.lines().get(0).drAmount()).isEqualByComparingTo("500000");
        assertThat(response.lines().get(0).crAmount()).isEqualByComparingTo("0");
        assertThat(response.lines().get(1).drAmount()).isEqualByComparingTo("0");
        assertThat(response.lines().get(1).crAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void testPreview_assetAccount_generatesDrLine() throws Exception {
        stubAccount("1200", AccountQualifier.ASSET, NormalBalance.DR);

        MockMultipartFile file = workbook(new String[][]{
                {"1200", "CC-ADM", "2000000", "Bank - HDFC opening balance"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).normalBalance()).isEqualTo("DR");
        assertThat(response.lines().get(0).drAmount()).isEqualByComparingTo("2000000");
        assertThat(response.lines().get(0).crAmount()).isEqualByComparingTo("0");
    }

    @Test
    void testPreview_liabilityAccount_generatesCrLine() throws Exception {
        stubAccount("2100", AccountQualifier.LIABILITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"2100", "CC-ADM", "300000", "Accounts Payable opening balance"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).normalBalance()).isEqualTo("CR");
        assertThat(response.lines().get(0).crAmount()).isEqualByComparingTo("300000");
        assertThat(response.lines().get(0).drAmount()).isEqualByComparingTo("0");
    }

    @Test
    void testPreview_balancedFile_isBalancedTrue() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("3100", AccountQualifier.EQUITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"},
                {"3100", "CC-ADM", "500000", "Partners Capital"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.isBalanced()).isTrue();
        assertThat(response.imbalanceAmount()).isEqualByComparingTo("0");
    }

    @Test
    void testPreview_unbalancedFile_isBalancedFalse() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("3100", AccountQualifier.EQUITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"},
                {"3100", "CC-ADM", "300000", "Partners Capital"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.isBalanced()).isFalse();
        assertThat(response.imbalanceAmount()).isEqualByComparingTo("200000");
    }

    @Test
    void testPreview_invalidAccount_returnsError() throws Exception {
        when(dimensionValueRepository.findByFinanceDimensionIdAndCodeAndIsActiveTrue(naturalAcctDim.getId(), "9999"))
                .thenReturn(Optional.empty());

        MockMultipartFile file = workbook(new String[][]{
                {"9999", "CC-ADM", "1000", "Unknown account"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.errorLines()).isEqualTo(1);
        assertThat(response.lines().get(0).errorMessage()).contains("Account not found: 9999");
        assertThat(response.errors()).anyMatch(e -> e.contains("9999"));
    }

    // ── import ───────────────────────────────────────────────────────────────

    @Test
    void testImport_balancedFile_postsSuccessfully() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("3100", AccountQualifier.EQUITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"},
                {"3100", "CC-ADM", "500000", "Partners Capital"}
        });

        UUID journalId = UUID.randomUUID();
        when(aiePipelineService.ingest(any(AieImportRequest.class), eq("MANUAL"), eq("OPENING")))
                .thenReturn(AieImportResponse.builder()
                        .status("POSTED")
                        .journalHeaderId(journalId)
                        .journalNumber("JE-0001")
                        .message("Batch imported and posted successfully.")
                        .errors(List.of())
                        .build());

        OpeningBalanceImportResponse response = service.importBalances(
                file, UUID.randomUUID(), ledgerId, UUID.randomUUID(), "accountant@orbinox.com");

        assertThat(response.success()).isTrue();
        assertThat(response.journalHeaderId()).isEqualTo(journalId);
        assertThat(response.postedLines()).isEqualTo(2);
        assertThat(response.totalLines()).isEqualTo(2);
    }

    @Test
    void testImport_unbalancedFile_returnsError() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("3100", AccountQualifier.EQUITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"},
                {"3100", "CC-ADM", "300000", "Partners Capital"}
        });

        OpeningBalanceImportResponse response = service.importBalances(
                file, UUID.randomUUID(), ledgerId, UUID.randomUUID(), "accountant@orbinox.com");

        assertThat(response.success()).isFalse();
        assertThat(response.postedLines()).isZero();
        assertThat(response.message()).contains("do not balance");
        verify(aiePipelineService, never()).ingest(any(), any(), any());
    }

    @Test
    void testImport_eventIdPrefixedWithOB() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("3100", AccountQualifier.EQUITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"},
                {"3100", "CC-ADM", "500000", "Partners Capital"}
        });

        when(aiePipelineService.ingest(any(AieImportRequest.class), eq("MANUAL"), eq("OPENING")))
                .thenReturn(AieImportResponse.builder()
                        .status("POSTED")
                        .journalHeaderId(UUID.randomUUID())
                        .journalNumber("JE-0002")
                        .message("Batch imported and posted successfully.")
                        .errors(List.of())
                        .build());

        service.importBalances(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(), "accountant@orbinox.com");

        ArgumentCaptor<AieImportRequest> captor = ArgumentCaptor.forClass(AieImportRequest.class);
        verify(aiePipelineService).ingest(captor.capture(), eq("MANUAL"), eq("OPENING"));
        assertThat(captor.getValue().eventId()).startsWith("OB-");
        assertThat(captor.getValue().sourceSystem()).isEqualTo("OPENING_BALANCE");
    }

    @Test
    void testImport_opposingAmountIsNullNotZero() throws Exception {
        // gl.journal_line's ck_debit_or_credit constraint requires the
        // non-applicable side to be NULL, not zero.
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);
        stubAccount("3100", AccountQualifier.EQUITY, NormalBalance.CR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"},
                {"3100", "CC-ADM", "500000", "Partners Capital"}
        });

        when(aiePipelineService.ingest(any(AieImportRequest.class), eq("MANUAL"), eq("OPENING")))
                .thenReturn(AieImportResponse.builder()
                        .status("POSTED")
                        .journalHeaderId(UUID.randomUUID())
                        .journalNumber("JE-0003")
                        .message("Batch imported and posted successfully.")
                        .errors(List.of())
                        .build());

        service.importBalances(file, UUID.randomUUID(), ledgerId, UUID.randomUUID(), "accountant@orbinox.com");

        ArgumentCaptor<AieImportRequest> captor = ArgumentCaptor.forClass(AieImportRequest.class);
        verify(aiePipelineService).ingest(captor.capture(), eq("MANUAL"), eq("OPENING"));

        AieLineRequest drLine = captor.getValue().lines().get(0);
        assertThat(drLine.debitAmount()).isEqualByComparingTo("500000");
        assertThat(drLine.creditAmount()).isNull();

        AieLineRequest crLine = captor.getValue().lines().get(1);
        assertThat(crLine.creditAmount()).isEqualByComparingTo("500000");
        assertThat(crLine.debitAmount()).isNull();
    }

    @Test
    void testPreview_opposingAmountShownAsZeroForDisplay() throws Exception {
        stubAccount("1100", AccountQualifier.ASSET, NormalBalance.DR);

        MockMultipartFile file = workbook(new String[][]{
                {"1100", "CC-ADM", "500000", "Cash"}
        });

        OpeningBalancePreviewResponse response = service.preview(file, UUID.randomUUID(), ledgerId, UUID.randomUUID());

        assertThat(response.lines().get(0).drAmount()).isEqualByComparingTo("500000");
        assertThat(response.lines().get(0).crAmount()).isEqualByComparingTo("0");
    }
}
