package com.invoice.extractor.service;

import com.invoice.extractor.model.InvoiceData;

public interface GenericExtractionService {
    InvoiceData extract(String rawText);
}
