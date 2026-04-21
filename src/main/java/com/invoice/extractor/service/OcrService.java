package com.invoice.extractor.service;

import com.invoice.extractor.model.InvoiceOcrDocument;
import org.springframework.web.multipart.MultipartFile;

public interface OcrService {
    String extractText(MultipartFile file);

    default InvoiceOcrDocument extractDocument(MultipartFile file) {
        return InvoiceOcrDocument.single(extractText(file));
    }
}
