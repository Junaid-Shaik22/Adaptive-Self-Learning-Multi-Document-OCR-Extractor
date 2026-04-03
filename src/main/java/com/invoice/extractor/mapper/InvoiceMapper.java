package com.invoice.extractor.mapper;

import com.invoice.extractor.model.InvoiceData;

public interface InvoiceMapper {
    InvoiceData map(Object... args);
}
