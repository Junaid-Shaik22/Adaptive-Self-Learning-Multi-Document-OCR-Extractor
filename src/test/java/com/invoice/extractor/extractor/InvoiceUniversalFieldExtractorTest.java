package com.invoice.extractor.extractor;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.model.InvoiceOcrDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceUniversalFieldExtractorTest {

    @Test
    void stopsBuyerAddressBeforePoGstinAndTableBoundaries() {
        String ocr = """
                Tax Invoice
                Buyer (Bill to)
                Department of Atomic Energy Stores
                HRPSU, NFC, P.O. ECIL, Hyderabad, Telangana - 500062
                P.O. No. GEMC-12345
                GSTIN : 36AAAGN1030Q1Z9
                Description Qty Rate Amount
                Service Charge 1 5000 5000
                """;
        InvoiceData context = new InvoiceData();
        context.setBuyerName("Department of Atomic Energy Stores");

        InvoiceUniversalFieldExtractor.Result result = new InvoiceUniversalFieldExtractor()
                .extract(InvoiceOcrDocument.single(ocr), context);

        assertNotNull(result.getBuyerAddress());
        assertTrue(result.getBuyerAddress().contains("HRPSU"));
        assertFalse(result.getBuyerAddress().contains("GEMC-12345"));
        assertFalse(result.getBuyerAddress().toLowerCase().contains("gstin"));
        assertFalse(result.getBuyerAddress().toLowerCase().contains("description"));
    }
}
