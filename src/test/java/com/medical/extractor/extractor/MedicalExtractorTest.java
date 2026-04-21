package com.medical.extractor.extractor;

import com.medical.extractor.model.MedicalLeaveData;
import com.medical.extractor.model.MedicalOcrDocument;
import com.medical.extractor.model.MedicalOcrPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MedicalExtractorTest {

    @Test
    void extractsStructuredMedicalLeaveFieldsFromMultiPageDocument() {
        MedicalOcrDocument document = new MedicalOcrDocument(List.of(
                new MedicalOcrPage(1, "p1", """
                        NUCLEAR FUEL COMPLEX
                        Medical Leave Certificate
                        This is to certify that Mr. Ramesh Kumar is suffering from viral fever.
                        He is advised rest from 01/04/2026 to 05/04/2026.
                        """),
                new MedicalOcrPage(2, "p2", """
                        Period of absence 5 days
                        Doctor Signature
                        Reg No 12345
                        """)
        ));

        MedicalLeaveData data = new MedicalExtractor().extract(document);

        assertEquals("NUCLEAR FUEL COMPLEX", data.getOrganizationName());
        assertEquals("Ramesh Kumar", data.getApplicantName());
        assertEquals("01-04-2026", data.getFromDate());
        assertEquals("05-04-2026", data.getToDate());
        assertEquals("5", data.getTotalAbsentDays());
    }

    @Test
    void prefersLeaveCertificatePageOverFitnessPageWhenBothExist() {
        MedicalOcrDocument document = new MedicalOcrDocument(List.of(
                new MedicalOcrPage(1, "p1", """
                        GOVERNMENT OF INDIA
                        DEPARTMENT OF ATOMIC ENERGY
                        NUCLEAR FUEL COMPLEX
                        MEDICAL CERTIFICATE OF FITNESS TO RETURN TO DUTY
                        I, Dr: BHANUKIRAN VUTLA do hereby certify that I have carefully examined
                        Shri/Smt/Kum M.SIRISHA (1628101) of Nuclear Fuel Complex
                        and find that he /she is now fit to resume duties from 31-Jan-2026
                        """),
                new MedicalOcrPage(2, "p2", """
                        GOVERNMENT OF INDIA
                        DEPARTMENT OF ATOMIC ENERGY
                        NUCLEAR FUEL COMPLEX
                        MEDICAL CERTIFICATE FOR NON-GAZETTED OFFICERS
                        RECOMMENDED FOR LEAVE OR EXTENSION OR COMMUNICATION OF LEAVE
                        I, Dr: BHANUKIRAN VUTLA after careful examination of the case hereby certify that
                        Shri/Smt/Kum M.SIRISHA (1628101) of Department of Atomic Energy, Nuclear Fuel Complex
                        is suffering from pharyngitis and febrile illness
                        and I consider that a period of absence from duty of 1
                        days with effect from 30-Jan-2026 to 30-Jan-2026 is absolutely necessary
                        """)
        ));

        MedicalLeaveData data = new MedicalExtractor().extract(document);

        assertEquals("NUCLEAR FUEL COMPLEX", data.getOrganizationName());
        assertEquals("M.SIRISHA", data.getApplicantName());
        assertEquals("30-01-2026", data.getFromDate());
        assertEquals("30-01-2026", data.getToDate());
        assertEquals("1", data.getTotalAbsentDays());
    }

    @Test
    void extractsHospitalStyleCertificatesAndComputesDaysFromRange() {
        MedicalOcrDocument document = MedicalOcrDocument.single("""
                THATHA HOSPITA
                MEDICAL & FITNESS CERTIFICATE
                This is to certify that Mr / Mrs / Ms P Anand aged about 36 years
                She / He is advised rest from 16.08.2024 to 22.08.2024
                She / He is fit to resume duty from 23.08.2024
                """);

        MedicalLeaveData data = new MedicalExtractor().extract(document);

        assertEquals("THATHA HOSPITAL", data.getOrganizationName());
        assertEquals("P Anand", data.getApplicantName());
        assertEquals("16-08-2024", data.getFromDate());
        assertEquals("22-08-2024", data.getToDate());
        assertEquals("7", data.getTotalAbsentDays());
    }

    @Test
    void ignoresUnrelatedCircularDocuments() {
        MedicalOcrDocument document = new MedicalOcrDocument(List.of(
                new MedicalOcrPage(1, "p1", """
                        Government of India
                        Department of Atomic Energy
                        Nuclear Fuel Complex
                        Recruitment-III
                        Circular
                        Consumption of mandatory courses on iGoT and APAR linkage
                        """),
                new MedicalOcrPage(2, "p2", """
                        Attention is invited to previous circular
                        mandatory courses are to be completed on or before 31.03.2026
                        """)
        ));

        MedicalLeaveData data = new MedicalExtractor().extract(document);

        assertNull(data.getOrganizationName());
        assertNull(data.getApplicantName());
        assertNull(data.getFromDate());
        assertNull(data.getToDate());
        assertNull(data.getTotalAbsentDays());
    }

    @Test
    void returnsNullsForInvalidNoisyCandidates() {
        MedicalOcrDocument document = MedicalOcrDocument.single("""
                Medical Certificate
                GOVERNMENT OF INDIA
                This is to certify that @@##$$
                from 45/78/9999 to 99/99/9999
                period of absence XX days
                """);

        MedicalLeaveData data = new MedicalExtractor().extract(document);

        assertNull(data.getOrganizationName());
        assertNull(data.getApplicantName());
        assertNull(data.getFromDate());
        assertNull(data.getToDate());
        assertNull(data.getTotalAbsentDays());
    }
}
