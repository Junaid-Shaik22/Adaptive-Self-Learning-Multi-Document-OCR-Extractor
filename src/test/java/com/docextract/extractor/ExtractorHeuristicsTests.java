package com.docextract.extractor;

import com.docextract.model.ExtractionResult;
import com.docextract.util.FieldValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtractorHeuristicsTests {

    private final PanExtractor panExtractor = new PanExtractor(new FieldValidator());
    private final AadhaarExtractor aadhaarExtractor = new AadhaarExtractor(new FieldValidator());
    private final DrivingLicenseExtractor drivingLicenseExtractor = new DrivingLicenseExtractor(new FieldValidator());

    @Test
    void panExtractorPrefersStructuredFieldsOverNoisyLines() {
        String cleanedText = """
                INCOME TAX DEPARTMENT
                GOVT. OF INDIA
                PERMANENT ACCOUNT NUMBER CARD
                GOS OY MA BE NEE
                CSTPJ6160M
                74 / NAME
                SHAIK MOHD JUNAID
                FATHER'S NAME
                SHAIK KHADER VALI
                DATE OF BIRTH
                45/06/2004
                15/06/2004
                """;

        ExtractionResult result = panExtractor.extract(cleanedText);

        assertEquals("CSTPJ6160M", result.getPanNumber());
        assertEquals("SHAIK MOHD JUNAID", result.getName());
        assertEquals("SHAIK KHADER VALI", result.getFatherName());
        assertEquals("15/06/2004", result.getDob());
    }

    @Test
    void panExtractorCleansFatherNameFromTrailingNoise() {
        String cleanedText = """
                INCOMBTAXDERARTMENT GOVT. OF INDIA
                PERMANENT ACCOUNT NUMBER CARD
                -SHAIKMOHDJUNAID OE EE
                SHAIK MOHD JUNAID
                FART T FATHER'S NAME NC ENEE OR CCE ERR
                SHAIK KHADER VALI NG SE SE
                45/06/2004
                15/06/2004
                CSTPJ6160M
                """;

        ExtractionResult result = panExtractor.extract(cleanedText);

        assertEquals("CSTPJ6160M", result.getPanNumber());
        assertEquals("SHAIK KHADER VALI", result.getFatherName());
        assertEquals("15/06/2004", result.getDob());
    }

    @Test
    void aadhaarExtractorDoesNotTreatDobAsAddress() {
        String cleanedText = """
                GOVERNMENT OF INDIA
                JOHN DOE
                DOB: 01/01/1990
                MALE
                3550 7368 0279
                VID: 9106 6907 1147 1273
                """;

        ExtractionResult result = aadhaarExtractor.extract(cleanedText);

        assertEquals("3550 7368 0279", result.getAadhaarNumber());
        assertEquals("01/01/1990", result.getDob());
        assertEquals("JOHN DOE", result.getName());
        assertNull(result.getAddress());
    }

    @Test
    void drivingLicenseExtractorFindsCompactDlNumberAndSkipsParentNameFromAddress() {
        String cleanedText = """
                INDIAN UNION DRIVING LICENCE
                TELANGANA STATE
                TSO
                1220180013985
                MOHD FARAZUDDIN
                MOHAMMED MOIZUDDIN
                19-1-1067
                DOODH BOWLI
                BANDAL GUDA
                BAHADURPURA
                HYDERABAD - 500064
                SIGNATURE
                """;

        ExtractionResult result = drivingLicenseExtractor.extract(cleanedText);

        assertEquals("TS01220180013985", result.getDlNumber());
        assertEquals("MOHD FARAZUDDIN", result.getName());
        assertEquals("19-1-1067, DOODH BOWLI, BANDAL GUDA, BAHADURPURA, HYDERABAD - 500064", result.getAddress());
        assertNull(result.getDob());
        assertNull(result.getValidationErrors());
    }
}
