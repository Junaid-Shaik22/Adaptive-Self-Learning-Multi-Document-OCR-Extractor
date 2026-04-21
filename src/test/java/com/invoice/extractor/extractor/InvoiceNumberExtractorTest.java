package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoiceNumberExtractorTest {

    @Test
    void prefersAnchoredShortNumericToken() {
        String ocr = """
                Tax Invoice
                Invoice No : 7
                Date : 01.04.2026
                """;
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(ocr);

        FieldExtractionResult<String> result = new InvoiceNumberExtractor().extractResult(zones);

        assertEquals("7", result.getValue());
        assertEquals("priority", result.getMethod());
    }
}
