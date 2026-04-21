package com.invoice.extractor.extractor;

import com.invoice.extractor.service.impl.LineIndexingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtotalExtractorTest {

    @Test
    void computesSubtotalFromTotalMinusTaxWhenNoBetterSignalExists() {
        String ocr = """
                Tax Invoice
                Item Qty Rate Amount
                Product A 1 20000 20000
                CGST 9% 2160
                SGST 9% 2160
                Grand Total 24000
                """;
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(ocr);

        FieldExtractionResult<String> result = new SubtotalExtractor().extractResult(zones, 24000.0, 4320.0);

        assertEquals("19680", result.getValue());
    }

    @Test
    void usesTotalMinusTaxWhenMatchingCandidateExists() {
        String ocr = """
                Tax Invoice
                Taxable Value 20000
                CGST 9% 2160
                SGST 9% 2160
                Grand Total 24320
                """;
        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(ocr);

        FieldExtractionResult<String> result = new SubtotalExtractor().extractResult(zones, 24320.0, 4320.0);

        assertEquals("20000", result.getValue());
    }
}
