package com.evyoog.gl.aie.excel.api;

import com.evyoog.gl.aie.dto.AieImportRequest;
import com.evyoog.gl.aie.dto.AieImportResponse;
import com.evyoog.gl.aie.excel.service.ExcelParserService;
import com.evyoog.gl.aie.service.AiePipelineService;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/aie/excel")
@RequiredArgsConstructor
@Tag(name = "GL-17 AIE Excel Import")
public class ExcelImportController {

    private final ExcelParserService excelParserService;
    private final AiePipelineService aiePipelineService;

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('gl:aie:import')")
    @Operation(summary = "Parse an uploaded Excel file and import/post it via the GL-16 AIE pipeline")
    public ResponseEntity<ApiResponse<AieImportResponse>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("legalEntityId") UUID legalEntityId,
            @RequestParam("ledgerId") UUID ledgerId,
            @RequestParam("accountingPeriodId") UUID accountingPeriodId,
            @RequestParam("createdBy") String createdBy,
            @RequestParam(value = "sourceSystem", defaultValue = "EXCEL_UPLOAD") String sourceSystem
    ) throws IOException {

        if (file.isEmpty()) {
            throw new EvyoogException("EMPTY_FILE", "Uploaded file is empty.", HttpStatus.BAD_REQUEST);
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.endsWith(".xlsx") || filename.endsWith(".xls"))) {
            throw new EvyoogException("INVALID_FILE_TYPE",
                    "Only Excel files (.xlsx, .xls) are accepted.", HttpStatus.BAD_REQUEST);
        }

        AieImportRequest request = excelParserService.parse(
                file, legalEntityId, ledgerId, accountingPeriodId, createdBy, sourceSystem);

        AieImportResponse response = aiePipelineService.ingest(request);

        HttpStatus status = "POSTED".equals(response.status()) ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('gl:aie:view')")
    @Operation(summary = "Download a customer-specific Excel template (dimension columns follow the Ledger's COA Structure) for GL-17 journal import")
    public ResponseEntity<byte[]> downloadTemplate(@RequestParam UUID ledgerId) throws IOException {
        byte[] template = excelParserService.generateTemplate(ledgerId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=journal_import_template.xlsx")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(template);
    }
}
