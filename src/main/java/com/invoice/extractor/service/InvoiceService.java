package com.invoice.extractor.service;

import com.invoice.extractor.model.InvoiceData;
import org.springframework.web.multipart.MultipartFile;

public interface InvoiceService {
    InvoiceData processInvoice(MultipartFile file);
}
