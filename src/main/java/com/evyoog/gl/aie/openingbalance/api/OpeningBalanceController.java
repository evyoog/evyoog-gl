package com.evyoog.gl.aie.openingbalance.api;

import com.evyoog.gl.aie.openingbalance.dto.OpeningBalanceImportResponse;
import com.evyoog.gl.aie.openingbalance.dto.OpeningBalancePreviewResponse;
import com.evyoog.gl.aie.openingbalance.service.OpeningBalanceService;
import com.evyoog.gl.common.exception.EvyoogException;
import com.evyoog.gl.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gl/opening-balances")
@RequiredArgsConstructor
@Tag(name = "Opening Balance Import")
public class OpeningBalanceController {

    private final OpeningBalanceService openingBalanceService;

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('gl:journal:create')")
    @Operation(summary = "Download the Opening Balance Excel template (dimension columns follow the Ledger's COA Structure)")
    public ResponseEntity<byte[]> downloadTemplate(@RequestParam UUID ledgerId) throws IOException {
        byte[] template = openingBalanceService.generateTemplate(ledgerId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=opening_balance_template.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(template);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('gl:journal:create')")
    @Operation(summary = "Preview an Opening Balance Excel upload — validates and auto-classifies DR/CR before posting")
    public ResponseEntity<ApiResponse<OpeningBalancePreviewResponse>> preview(
            @RequestParam UUID legalEntityId,
            @RequestParam UUID ledgerId,
            @RequestParam UUID accountingPeriodId,
            @RequestPart MultipartFile file) throws IOException {

        validateFile(file);
        OpeningBalancePreviewResponse response = openingBalanceService.preview(
                file, legalEntityId, ledgerId, accountingPeriodId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('gl:journal:create')")
    @Operation(summary = "Import and post Opening Balances via the AIE pipeline")
    public ResponseEntity<ApiResponse<OpeningBalanceImportResponse>> importBalances(
            @RequestParam UUID legalEntityId,
            @RequestParam UUID ledgerId,
            @RequestParam UUID accountingPeriodId,
            @RequestParam String createdBy,
            @RequestPart MultipartFile file) throws IOException {

        validateFile(file);
        OpeningBalanceImportResponse response = openingBalanceService.importBalances(
                file, legalEntityId, ledgerId, accountingPeriodId, createdBy);

        HttpStatus status = response.success() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new EvyoogException("EMPTY_FILE", "Uploaded file is empty.", HttpStatus.BAD_REQUEST);
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.endsWith(".xlsx") || filename.endsWith(".xls"))) {
            throw new EvyoogException("INVALID_FILE_TYPE",
                    "Only Excel files (.xlsx, .xls) are accepted.", HttpStatus.BAD_REQUEST);
        }
    }
}
