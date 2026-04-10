package com.invoice.extractor.controller;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.service.InvoiceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/invoice")
@Tag(name = "Invoice Extraction", description = "Extract structured data from invoice PDFs and images")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @Operation(
            summary = "Extract data from an invoice",
            description = """
                    Accepts an invoice image (JPEG/PNG/TIFF/BMP) or PDF and returns
                    structured JSON data extracted from the document.

                    **Extracted Fields:**
                    - Invoice Number
                    - Invoice Date
                    - Vendor Name and GSTIN
                    - Buyer Name and GSTIN
                    - Subtotal, Tax Amount, Total Amount
                    - Line Items (when detected)

                    **Supported Input Formats:**
                    - JPEG, PNG, TIFF, BMP
                    - PDF (single or multi-page)

                    **Processing Pipeline:**
                    Upload -> OCR -> Text Cleaning -> Field Extraction -> Template Matching -> JSON
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Invoice extracted successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = InvoiceData.class)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Invalid file or unsupported format"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "Internal server error during processing"
            )
    })
    @PostMapping(
            value = "/extract",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<InvoiceData> extractInvoice(
            @Parameter(
                    description = "Invoice image (JPEG/PNG/TIFF/BMP) or PDF file",
                    required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE)
            )
            @RequestParam("file") MultipartFile file) {

        InvoiceData data = invoiceService.processInvoice(file);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/test")
    public String test() {
        return "API Working";
    }
}
