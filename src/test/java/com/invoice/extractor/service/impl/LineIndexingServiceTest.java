package com.invoice.extractor.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineIndexingServiceTest {

    @Test
    void splitsWideGapLineIntoLeftAndRightColumns() {
        String ocr = "ACME INDUSTRIES PRIVATE LIMITED        Invoice No : 7\n"
                + "GSTIN : 29ABCDE1234F1Z5                Date : 01.04.2026";

        LineIndexingService.Zones zones = LineIndexingService.indexLinesAndZones(ocr);

        assertEquals(4, zones.allLines.size());
        assertEquals(LineIndexingService.Column.LEFT_COLUMN, zones.allLines.get(0).getColumn());
        assertEquals(LineIndexingService.Column.RIGHT_COLUMN, zones.allLines.get(1).getColumn());
        assertTrue(zones.allLines.get(0).getText().contains("ACME INDUSTRIES"));
        assertTrue(zones.allLines.get(1).getText().contains("Invoice No"));
    }
}
