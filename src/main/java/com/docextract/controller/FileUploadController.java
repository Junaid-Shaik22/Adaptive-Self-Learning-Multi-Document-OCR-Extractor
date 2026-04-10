package com.docextract.controller;

import com.docextract.model.ApiResponse;
import com.docextract.model.ExtractionResult;
import com.docextract.service.DocumentProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * FileUploadController – single endpoint REST controller.
 *
 * POST /extract-document
 *   Input : multipart/form-data  (key = "file")
 *   Output: application/json
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Document Extraction", description = "Extract structured data from Indian ID documents")
public class FileUploadController {

    private final DocumentProcessingService documentProcessingService;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /extract-document
    // ─────────────────────────────────────────────────────────────────────────

    @Operation(
        summary     = "Extract data from an Indian ID document",
        description = """
            Accepts an image (JPEG/PNG/TIFF/BMP) or PDF and returns
            structured JSON data extracted from the document.
            
            **Supported Documents:**
            - Aadhaar Card (front / back / full)
            - PAN Card
            - Driving License
            
            **Processing Pipeline:**
            Upload → OpenCV Preprocessing → Tesseract OCR → Text Cleaning
            → Document Type Detection → Field Extraction → Validation → JSON
            """
    )
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description  = "Document extracted successfully",
            content      = @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema    = @Schema(implementation = ApiResponse.class)
            )
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description  = "Invalid file or unsupported format"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description  = "Document type could not be detected"
        ),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "500",
            description  = "Internal server error during processing"
        )
    })
    @PostMapping(
        value    = "/extract-document",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> extractDocument(
        @Parameter(
            description = "Image (JPEG/PNG/TIFF/BMP) or PDF file of the ID document",
            required    = true,
            content     = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE)
        )
        @RequestPart("file") MultipartFile file
    ) {
        String rawFilename = file.getOriginalFilename();
        String originalFilename = (rawFilename != null) ? rawFilename : "unknown";

        String rawContentType = file.getContentType();
        String contentTypeHeader = (rawContentType != null) ? rawContentType : "application/octet-stream";

        log.info("Received extraction request: file='{}' size={} bytes type='{}'",
                originalFilename,
                file.getSize(),
                contentTypeHeader);

        // ── Basic validation ──────────────────────────────────────────────
        if (file.isEmpty()) {
            log.warn("Empty file received");
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("File is empty", "Please upload a valid image or PDF file."));
        }

        String contentType = contentTypeHeader.toLowerCase();
        String fileName    = originalFilename.toLowerCase();

        boolean isImage = contentType.contains("image") ||
                          fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
                          fileName.endsWith(".png")  || fileName.endsWith(".tiff") ||
                          fileName.endsWith(".tif")  || fileName.endsWith(".bmp");

        boolean isPdf   = contentType.contains("pdf") || fileName.endsWith(".pdf");

        if (!isImage && !isPdf) {
            log.warn("Unsupported file type: {}", contentType);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail(
                            "Unsupported file type",
                            "Supported formats: JPEG, PNG, TIFF, BMP, PDF. Received: " + contentType
                    ));
        }

        // ── Process ───────────────────────────────────────────────────────
        try {
            ExtractionResult result = documentProcessingService.process(file, isPdf);
            log.info("Extraction complete: documentType={} file='{}'",
                    result.getDocumentType(), originalFilename);
            return ResponseEntity.ok(ApiResponse.ok(result, originalFilename));

        } catch (IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ApiResponse.fail("Bad request", e.getMessage()));

        } catch (Exception e) {
            log.error("Processing failed for file '{}'", originalFilename, e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail("Processing failed", e.getMessage()));
        }
    }

    // ─── Health check ─────────────────────────────────────────────────────────

    @Operation(summary = "Health check", description = "Returns OK if the service is running")
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Document Intelligence System is running ✓");
    }
}
