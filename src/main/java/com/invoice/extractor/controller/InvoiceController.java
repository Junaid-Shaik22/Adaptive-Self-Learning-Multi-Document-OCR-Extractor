package com.invoice.extractor.controller;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.service.InvoiceService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/invoice")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InvoiceData> extractInvoice(
            @RequestParam("file") MultipartFile file) {

        InvoiceData data = invoiceService.processInvoice(file);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/test")
    public String test() {
        return "API Working";
    }
}