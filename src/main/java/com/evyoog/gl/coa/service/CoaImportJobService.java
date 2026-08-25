package com.evyoog.gl.coa.service;

import com.evyoog.gl.coa.domain.CoaImportJob;
import com.evyoog.gl.coa.domain.ImportJobStatus;
import com.evyoog.gl.coa.dto.AccountResponse;
import com.evyoog.gl.coa.dto.CoaImportJobResponse;
import com.evyoog.gl.coa.dto.CreateAccountRequest;
import com.evyoog.gl.coa.excel.dto.ParsedAccountRow;
import com.evyoog.gl.coa.excel.service.CoaExcelParserService;
import com.evyoog.gl.coa.excel.service.CoaImportRowService;
import com.evyoog.gl.coa.mapper.CoaImportJobMapper;
import com.evyoog.gl.coa.repository.CoaImportJobRepository;
import com.evyoog.gl.common.audit.domain.AuditAction;
import com.evyoog.gl.common.audit.service.AuditService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.exception.ResourceNotFoundException;
import com.evyoog.gl.dimension.domain.AccountQualifier;
import com.evyoog.gl.dimension.domain.DimensionType;
import com.evyoog.gl.dimension.domain.DimensionValue;
import com.evyoog.gl.dimension.domain.FinanceDimension;
import com.evyoog.gl.dimension.domain.NormalBalance;
import com.evyoog.gl.dimension.repository.DimensionValueRepository;
import com.evyoog.gl.dimension.repository.FinanceDimensionRepository;
import com.evyoog.gl.ledger.domain.Ledger;
import com.evyoog.gl.ledger.repository.LedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GL-05 exposes GET only. GL-06 adds {@link #importFromExcel} — parses an
 * uploaded Chart of Accounts Excel file and creates accounts via the existing
 * GL-05 {@link ChartOfAccountsService}, one row at a time, through
 * {@link CoaImportRowService} so a bad row's transaction rollback never
 * poisons the enclosing job-tracking transaction. A bad row never aborts the
 * whole import: each row's failure is caught and recorded in the job's
 * error_details.
 */
@Service
@RequiredArgsConstructor
public class CoaImportJobService {

    private static final Set<String> VALID_QUALIFIERS = Set.of(
            "ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE", "BUDGETARY");

    private final CoaImportJobRepository repository;
    private final CoaImportJobMapper mapper;
    private final CoaExcelParserService excelParserService;
    private final CoaImportRowService coaImportRowService;
    private final LedgerRepository ledgerRepository;
    private final FinanceDimensionRepository financeDimensionRepository;
    private final DimensionValueRepository dimensionValueRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public CoaImportJobResponse getById(UUID id) {
        CoaImportJob entity = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CoaImportJob", id));
        return mapper.toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<CoaImportJobResponse> list(UUID ledgerId) {
        return repository.findByLedgerId(ledgerId).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public CoaImportJobResponse importFromExcel(MultipartFile file, UUID ledgerId, UUID createdBy) throws IOException {
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .orElseThrow(() -> new ResourceNotFoundException("Ledger", ledgerId));

        FinanceDimension dimension = financeDimensionRepository
                .findByLedgerIdAndDimensionTypeAndIsActiveTrue(ledgerId, DimensionType.NATURAL_ACCOUNT)
                .orElseThrow(() -> new EvyoogException("NO_NATURAL_ACCOUNT_DIM",
                        "No active Natural Account dimension found for this Ledger.", HttpStatus.NOT_FOUND));

        String performedBy = createdBy != null ? createdBy.toString() : "system";

        CoaImportJob job = CoaImportJob.builder()
                .ledger(ledger)
                .financeDimension(dimension)
                .fileName(file.getOriginalFilename())
                .status(ImportJobStatus.PROCESSING)
                .startedAt(Instant.now())
                .createdBy(createdBy)
                .build();
        job = repository.saveAndFlush(job);

        List<ParsedAccountRow> rows = excelParserService.parse(file);
        List<Map<String, Object>> rowErrors = new ArrayList<>();
        Map<String, UUID> codeToIdInThisImport = new LinkedHashMap<>();
        int successCount = 0;

        for (ParsedAccountRow row : rows) {
            String code = row.code();
            try {
                if (code == null || code.isBlank()) {
                    rowErrors.add(rowError(row.rowNumber(), null, "MISSING_REQUIRED_FIELD", "Account code is required"));
                    continue;
                }
                if (row.name() == null || row.name().isBlank()) {
                    rowErrors.add(rowError(row.rowNumber(), code, "MISSING_REQUIRED_FIELD", "Account name is required"));
                    continue;
                }
                String qualifierRaw = row.qualifier() == null ? null : row.qualifier().trim().toUpperCase();
                if (qualifierRaw == null || !VALID_QUALIFIERS.contains(qualifierRaw)) {
                    rowErrors.add(rowError(row.rowNumber(), code, "INVALID_QUALIFIER",
                            "Qualifier must be one of ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE, BUDGETARY"));
                    continue;
                }
                AccountQualifier qualifier = AccountQualifier.valueOf(qualifierRaw);

                UUID parentId = null;
                if (row.parentCode() != null && !row.parentCode().isBlank()) {
                    parentId = codeToIdInThisImport.get(row.parentCode());
                    if (parentId == null) {
                        parentId = dimensionValueRepository
                                .findByFinanceDimensionIdAndCodeAndIsActiveTrue(dimension.getId(), row.parentCode())
                                .map(DimensionValue::getId)
                                .orElse(null);
                    }
                    if (parentId == null) {
                        rowErrors.add(rowError(row.rowNumber(), code, "PARENT_NOT_FOUND",
                                "Parent account with code '" + row.parentCode() + "' was not found"));
                        continue;
                    }
                }

                NormalBalance normalBalance = null;
                if (row.normalBalance() != null && !row.normalBalance().isBlank()) {
                    try {
                        normalBalance = NormalBalance.valueOf(row.normalBalance().trim().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        rowErrors.add(rowError(row.rowNumber(), code, "INVALID_NORMAL_BALANCE",
                                "normalBalance must be DR or CR"));
                        continue;
                    }
                }

                CreateAccountRequest request = new CreateAccountRequest(
                        ledgerId, code, row.name(), row.description(), parentId, qualifier,
                        row.isSummary(), row.isPostable(), normalBalance,
                        null, null, null, null,
                        null, null, null, null,
                        null, null, null, null);

                AccountResponse created = coaImportRowService.createAccountIsolated(request, performedBy);
                codeToIdInThisImport.put(code, created.id());
                successCount++;
            } catch (EvyoogException e) {
                rowErrors.add(rowError(row.rowNumber(), code, e.getCode(), e.getMessage()));
            } catch (Exception e) {
                rowErrors.add(rowError(row.rowNumber(), code, "UNEXPECTED_ERROR", e.getMessage()));
            }
        }

        if (rows.isEmpty()) {
            rowErrors.add(rowError(0, null, "NO_DATA_ROWS", "Excel file has no data rows."));
        }

        int errorCount = rowErrors.size();
        ImportJobStatus status;
        if (rows.isEmpty() || successCount == 0) {
            status = ImportJobStatus.FAILED;
        } else if (errorCount == 0) {
            status = ImportJobStatus.COMPLETED;
        } else {
            status = ImportJobStatus.COMPLETED_WITH_ERRORS;
        }

        job.setTotalRows(rows.size());
        job.setProcessedRows(rows.size());
        job.setSuccessRows(successCount);
        job.setErrorRows(errorCount);
        job.setErrorDetails(rowErrors.isEmpty() ? null : Map.of("errors", rowErrors));
        job.setStatus(status);
        job.setCompletedAt(Instant.now());
        job.setUpdatedBy(createdBy);

        CoaImportJob saved = repository.saveAndFlush(job);
        CoaImportJobResponse response = mapper.toResponse(saved);
        auditService.log(AuditAction.CREATE, "coa_import_job", saved.getId(), null, response, performedBy);

        return response;
    }

    private Map<String, Object> rowError(int rowNumber, String accountCode, String errorCode, String errorMessage) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("rowNumber", rowNumber);
        error.put("accountCode", accountCode == null ? "" : accountCode);
        error.put("errorCode", errorCode);
        error.put("errorMessage", errorMessage);
        return error;
    }
}
