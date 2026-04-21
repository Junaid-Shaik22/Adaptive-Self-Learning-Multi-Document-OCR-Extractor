package com.docextract.extractor;

import com.docextract.model.ExtractionResult;
import com.docextract.service.TextCleaningService;
import com.docextract.util.FieldValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExtractorHeuristicsTests {

    private final PanExtractor panExtractor = new PanExtractor(new FieldValidator());
    private final AadhaarExtractor aadhaarExtractor = new AadhaarExtractor(new FieldValidator());
    private final DrivingLicenseExtractor drivingLicenseExtractor = new DrivingLicenseExtractor(new FieldValidator());
    private final TextCleaningService textCleaningService = new TextCleaningService();

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
    void aadhaarExtractorHandlesTwoSideAadhaarAndKeepsFullHouseNumber() {
        String cleanedText = """
                GOVERNMENT OF INDIA
                UNIQUE IDENTIFICATION AUTHORITY OF INDIA
                ENROLMENT NO.: 2081/11373/50441
                TO
                SHAIK MOHD JUNAID
                S/O S KHADER VALI
                & 23-647/B/77
                PATEL NAGAR
                AMBERPET
                BESIDE NALLA POCHAMMA TEMPLE
                HYDERABAD ANDHRA PRADESH - 500013
                YOUR AADHAAR NO.:
                3550 7368 O279
                VID : 9106 6907 1147 1273
                GOVERNMENT OF INDIA
                SHALK M0HD JUNAID
                B4/DOB: 15/06/2004
                MALE
                """;

        ExtractionResult result = aadhaarExtractor.extract(cleanedText);

        assertEquals("3550 7368 0279", result.getAadhaarNumber());
        assertEquals("15/06/2004", result.getDob());
        assertEquals("Male", result.getGender());
        assertEquals("SHAIK MOHD JUNAID", result.getName());
        assertEquals("S/O S KHADER VALI, 23-647/B/77, PATEL NAGAR, AMBERPET, BESIDE NALLA POCHAMMA TEMPLE, HYDERABAD ANDHRA PRADESH - 500013", result.getAddress());
    }

    @Test
    void aadhaarExtractorPrefersChecksumValidNumberAndDobOverVidAndDateNoise() {
        String cleanedText = """
                GOVERNMENT OF INDIA
                UNIQUE IDENTIFICATION AUTHORITY OF INDIA
                TO
                SHAIK MOHD JUNAID
                S/O S KHADER VALI
                2-3-647/B/77
                PATEL NAGAR
                8 AMBERPET
                8 BESIDE NALLA POCHAMMA TEMPLE
                HYDERABAD ANDHRA PRADESH - 500013
                DOWNLOAD DATE: 07/08/2021
                ISSUE DATE: 18/08/2004
                YOUR AADHAAR NO. :
                3550 7368 O279
                VID : 5106 6907 1147 1273
                GOVERNMENT OF INDIA
                S4/D0B: 15/06/2004
                MALE
                """;

        ExtractionResult result = aadhaarExtractor.extract(cleanedText);

        assertEquals("3550 7368 0279", result.getAadhaarNumber());
        assertEquals("15/06/2004", result.getDob());
        assertEquals("SHAIK MOHD JUNAID", result.getName());
        assertEquals("S/O S KHADER VALI, 2-3-647/B/77, PATEL NAGAR, AMBERPET, BESIDE NALLA POCHAMMA TEMPLE, HYDERABAD ANDHRA PRADESH - 500013", result.getAddress());
    }

    @Test
    void aadhaarExtractorIgnoresTrailingGenderNoiseWhenChoosingName() {
        String cleanedText = """
                GOVERNMENT OF INDIA
                UNIQUE IDENTIFICATION AUTHORITY OF INDIA
                TO
                SHAIK MOHD JUNAID
                S/O S KHADER VALI
                2-3-647/B/77
                PATEL NAGAR
                AMBERPET
                YOUR AADHAAR NO.:
                3550 7368 0279
                VID : 9106 6907 1147 1273
                WTUCDSE MALE
                DOB: 15/06/2004
                MALE
                """;

        ExtractionResult result = aadhaarExtractor.extract(cleanedText);

        assertEquals("3550 7368 0279", result.getAadhaarNumber());
        assertEquals("15/06/2004", result.getDob());
        assertEquals("SHAIK MOHD JUNAID", result.getName());
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

    @Test
    void panExtractorHandlesSplitPanNumberAcrossLines() {
        String cleanedText = """
                INCOME TAX DEPARTMENT
                GOVT OF INDIA
                PERMANENT ACCOUNT NUMBER
                CSTPJ
                6160M
                SHAIK M0HD JUNAID
                SHAIK KHADER VALI
                DATE OF BIRTH
                15/O6/2004
                """;

        ExtractionResult result = panExtractor.extract(cleanedText);

        assertEquals("CSTPJ6160M", result.getPanNumber());
        assertEquals("SHAIK MOHD JUNAID", result.getName());
        assertEquals("SHAIK KHADER VALI", result.getFatherName());
        assertEquals("15/06/2004", result.getDob());
    }

    @Test
    void drivingLicenseExtractorAcceptsThreeDigitRtoNumbers() {
        String cleanedText = """
                DRIVING LICENCE
                AP12320181234567
                SHAIK MOHD JUNAID
                ADDRESS
                12-1-45/7
                HYDERABAD 500001
                """;

        ExtractionResult result = drivingLicenseExtractor.extract(cleanedText);

        assertEquals("AP12320181234567", result.getDlNumber());
        assertEquals("SHAIK MOHD JUNAID", result.getName());
        assertNull(result.getValidationErrors());
    }

    @Test
    void drivingLicenseExtractorRejectsSignatureNoiseAsName() {
        String cleanedText = """
                INDIAN UNION DRIVING LICENCE
                TELANGANA STATE
                TS01220180013985
                MOHD FARAZUDDIN
                MOHAMMED MOIZUDDIN
                19-1-1067
                DOODH BOWLI
                BANDAL GUDA
                BAHADURPURA
                HYDERABAD - 500064
                SIGNATURE LIGENEING AUTHERAY
                """;

        ExtractionResult result = drivingLicenseExtractor.extract(cleanedText);

        assertEquals("TS01220180013985", result.getDlNumber());
        assertEquals("MOHD FARAZUDDIN", result.getName());
        assertEquals("19-1-1067, DOODH BOWLI, BANDAL GUDA, BAHADURPURA, HYDERABAD - 500064", result.getAddress());
    }

    @Test
    void drivingLicenseExtractorStripsHeaderFragmentsFromAddressLines() {
        String cleanedText = """
                INDIAN UNION DRIVING LICENCE
                TELANGANA STATE
                TS01220180013985
                MOHD FARAZUDDIN
                19-1-1067, DOODH BOWLI, BANDAL GUDA, BAHADURPURA, INDIAN UNION DRIVING LICENCE, TELANGANA STATE
                HYDERABAD - 500064
                SIGNATURE
                """;

        ExtractionResult result = drivingLicenseExtractor.extract(cleanedText);

        assertEquals("MOHD FARAZUDDIN", result.getName());
        assertEquals("19-1-1067, DOODH BOWLI, BANDAL GUDA, BAHADURPURA, HYDERABAD - 500064", result.getAddress());
    }

    @Test
    void textCleaningPreservesDlPrefixOnNumericDominantLines() {
        String cleaned = textCleaningService.clean("""
                TSO 1220180013985
                D0B 15/O6/2004
                """);

        assertEquals("""
                TSO 1220180013985
                DOB 15/06/2004
                """.strip(), cleaned);
    }
}
