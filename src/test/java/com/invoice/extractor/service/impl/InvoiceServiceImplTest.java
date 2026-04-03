package com.invoice.extractor.service.impl;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.service.OcrService;
import com.invoice.extractor.template.JsonTemplateRepository;
import com.invoice.extractor.template.Template;
import com.invoice.extractor.util.RegexUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsGenericFieldsFromUnseenLayoutAndLearnsTemplate() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, invoiceOne());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[0]));

        assertEquals("INVA1023", data.getInvoiceNumber());
        assertEquals("05-Apr-2026", data.getInvoiceDate());
        assertEquals("ALPHA INDUSTRIES PRIVATE LIMITED", data.getVendorName());
        assertEquals("29ABCDE1234F1Z5", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("BETA RETAIL SOLUTIONS LLP"));
        assertEquals("27PQRSX6789L1Z2", data.getBuyerGstin());
        assertEquals("15000", data.getSubTotal());
        assertEquals("2700", data.getTaxAmount());
        assertEquals("17700", data.getTotalAmount());
        assertNotNull(data.getLineItems());
        assertFalse(data.getLineItems().isEmpty());

        List<Template> templates = new JsonTemplateRepository(templatePath).loadTemplates();
        assertEquals(1, templates.size());
    }

    @Test
    void reusesLearnedTemplateForSameLayoutWithDifferentValues() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl learningService = buildService(templatePath, invoiceOne());
        learningService.processInvoice(new MockMultipartFile("file", "invoice1.pdf", "application/pdf", new byte[0]));

        InvoiceServiceImpl extractionService = buildService(templatePath, invoiceTwo());
        InvoiceData data = extractionService.processInvoice(new MockMultipartFile("file", "invoice2.pdf", "application/pdf", new byte[0]));

        assertNotNull(data.getTemplateId());
        assertEquals("INVB2048", data.getInvoiceNumber());
        assertEquals("08-Apr-2026", data.getInvoiceDate());
        assertTrue(data.getBuyerName().contains("GAMMA BUSINESS SYSTEMS LLP"));
        assertEquals("07LMNOP4321Q1Z8", data.getBuyerGstin());
        assertEquals("19200", data.getSubTotal());
        assertEquals("3456", data.getTaxAmount());
        assertEquals("22656", data.getTotalAmount());
    }

    @Test
    void extractsFieldsFromNoisyTableDrivenOcrText() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, noisyRancoOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("B584", data.getInvoiceNumber());
        assertEquals("20-Jan-24", data.getInvoiceDate());
        assertTrue(data.getVendorName().contains("Ranco Industries"));
        assertEquals("24AAEFR7351M1ZW", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy- KOTA"));
        assertEquals("08AAAGN1030Q1Z8", data.getBuyerGstin());
        assertEquals("260680.51", data.getSubTotal());
        assertEquals("46922.49", data.getTaxAmount());
        assertEquals("307603", data.getTotalAmount());
        assertTrue(data.getLineItems().isEmpty());
        assertEquals("SUCCESS", data.getStatus());
    }

    @Test
    void extractsFieldsFromMessyMultiColumnOcrText() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, messyRancoOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("B584", data.getInvoiceNumber());
        assertEquals("20-Jan-24", data.getInvoiceDate());
        assertTrue(data.getVendorName().contains("Ranco Industries"));
        assertEquals("24AAEFR7351M1ZW", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy- KOTA"));
        assertEquals("08AAAGN1030Q1Z8", data.getBuyerGstin());
        assertEquals("260680.51", data.getSubTotal());
        assertEquals("46922.49", data.getTaxAmount());
        assertEquals("307603", data.getTotalAmount());
        assertTrue(data.getLineItems().isEmpty());
    }

    @Test
    void extractsFieldsFromRealScreenshotOcrPattern() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, screenshotStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("B584", data.getInvoiceNumber());
        assertEquals("20-Jan-24", data.getInvoiceDate());
        assertTrue(data.getVendorName().contains("Ranco Industries"));
        assertEquals("24AAEFR7351M1ZW", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy- KOTA"));
        assertEquals("08AAAGN1030Q1Z8", data.getBuyerGstin());
        assertEquals("260680.51", data.getSubTotal());
        assertEquals("46922.49", data.getTaxAmount());
        assertEquals("307603", data.getTotalAmount());
        assertTrue(data.getLineItems().isEmpty());
    }

    @Test
    void ignoresStaleTemplatesAndRepairsGstinChecksum() throws Exception {
        Path templatePath = tempDir.resolve("templates.json");
        Files.writeString(templatePath, """
                [
                  {
                    "templateId": "old-template",
                    "vendorName": "BS84 20-Jan-24",
                    "vendorGstin": "24AAEFR7351M1ZW",
                    "signature": "1c7669c4f201c9acbc1680e84639b72b",
                    "fieldPositions": {
                      "invoiceNumber": { "lineNumber": 2, "relativePosition": 1, "zone": "TOP", "keyword": "invoice no" }
                    }
                  }
                ]
                """);

        InvoiceServiceImpl service = buildService(templatePath, screenshotStyleOcr());
        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));
        List<Template> templates = new JsonTemplateRepository(templatePath).loadTemplates();

        assertNull(data.getTemplateId());
        assertEquals("B584", data.getInvoiceNumber());
        assertEquals("08AAAGN1030Q1Z8", data.getBuyerGstin());
        assertTrue(templates.stream().anyMatch(template -> template.getVersion() == TemplateServiceImpl.TEMPLATE_VERSION));
        assertTrue(RegexUtil.isValidGstin("08AAAGN1030Q1Z8"));
    }

    @Test
    void extractsBuyerGstinFromBuyerZoneNotDispatchOrTransport() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, buyerZoneDispatchNoiseOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("INV1002", data.getInvoiceNumber());
        assertEquals("02-Apr-2026", data.getInvoiceDate());
        assertEquals("29ABCDE1234F1Z5", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("OMEGA TRADERS LLP"));
        assertEquals("07LMNOP4321Q1Z8", data.getBuyerGstin());
        assertEquals("1000", data.getTotalAmount());
    }

    @Test
    void extractsLineItemsFromGenericTableWithMultilineDescriptions() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, multilineLineItemsOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertNotNull(data.getLineItems());
        assertEquals(2, data.getLineItems().size());
        assertTrue(data.getLineItems().get(0).getDescription().contains("Premium Stainless Steel Storage Cabinet"));
        assertEquals("94032010", data.getLineItems().get(0).getHsn());
        assertEquals("2", data.getLineItems().get(0).getQuantity());
        assertEquals("12500", data.getLineItems().get(0).getUnitPrice());
        assertEquals("25000", data.getLineItems().get(0).getAmount());
        assertTrue(data.getLineItems().get(1).getDescription().contains("Industrial Workbench"));
        assertEquals("94038900", data.getLineItems().get(1).getHsn());
        assertEquals("1", data.getLineItems().get(1).getQuantity());
        assertEquals("18000", data.getLineItems().get(1).getUnitPrice());
        assertEquals("18000", data.getLineItems().get(1).getAmount());
        assertEquals("43000", data.getSubTotal());
        assertEquals("7740", data.getTaxAmount());
        assertEquals("50740", data.getTotalAmount());
    }

    private InvoiceServiceImpl buildService(Path templatePath, String ocrText) {
        JsonTemplateRepository repository = new JsonTemplateRepository(templatePath);
        OcrService ocrService = file -> ocrText;
        return new InvoiceServiceImpl(
                ocrService,
                new TemplateServiceImpl(repository),
                new TemplateExtractionServiceImpl(),
                new TemplateLearningServiceImpl(repository)
        );
    }

    private String invoiceOne() {
        return """
                ALPHA INDUSTRIES PRIVATE LIMITED
                Plot 44 Industrial Estate
                GSTIN: 29ABCDE1234F1Z5
                Tax Invoice
                Invoice No: INVA1023
                Date: 05-Apr-2026

                Ship To
                BETA RETAIL SOLUTIONS LLP
                21 Market Street
                GSTIN: 27PQRSX6789L1Z2

                Item Description Qty Rate Amount
                Steel Cabinet 5 3000 15000
                Taxable Value 15000
                CGST 9% 1350
                SGST 9% 1350
                Grand Total 17700
                Amount Payable 17700
                Bank Details: ABC Bank A/C 123456789012
                """;
    }

    private String invoiceTwo() {
        return """
                ALPHA INDUSTRIES PRIVATE LIMITED
                Plot 44 Industrial Estate
                GSTIN: 29ABCDE1234F1Z5
                Tax Invoice
                Invoice No: INVB2048
                Date: 08-Apr-2026

                Ship To
                GAMMA BUSINESS SYSTEMS LLP
                12 Commerce Road
                GSTIN: 07LMNOP4321Q1Z8

                Item Description Qty Rate Amount
                Steel Cabinet 6 3200 19200
                Taxable Value 19200
                CGST 9% 1728
                SGST 9% 1728
                Grand Total 22656
                Amount Payable 22656
                Bank Details: ABC Bank A/C 998877665544
                """;
    }

    private String noisyRancoOcr() {
        return """
                TAX INVOICE(Page 2)
                Ranco Industries (2022-2023)
                Invoice NO.
                B584
                Dated
                20-Jan-24
                GSTINIUIN: 24AAEFR7351M1ZW
                Consignee (Ship to)
                Department of Atomic Energy- KOTA
                Directorate Of Purchase And Stores
                NFC KOTA PLANT SITE, RAWATBHATTA,
                PO, ANUSHAKTI (VIA) KOTA
                CHITTORGARH, RAJASTHAN-323303, India
                GSTINIUIN : 08AAAGN1030Q1Z8
                Buyer (Bill to)
                Department of Atomic Energy- KOTA
                Directorate Of Purchase And Stores
                NFC KOTA PLANT SITE, RAWATBHATTA,
                PO, ANUSHAKTI (VIA) KOTA
                CHITTORGARH, RAJASTHAN-323303, India
                GSTINIUIN : 08AAAGN1030Q1Z8
                Description of Goods HSN/SAC Quantity Rate Per Amount
                I G ST Output- 18%
                18%
                46,922.49
                Total
                1.000 set
                3,07,603.00
                Taxable Value 2,60,680.51
                IGST 18% 46,922.49
                Total Tax Amount 46,922.49
                """;
    }

    private String messyRancoOcr() {
        return """
                TAX INVOICE(Page 2) (ORIGINAL FOR RECIPIENT)
                Ranco Industries (2022-2023) Invoice No. e-Way Bil No.
                Fac Add- S . No-150, Plot No.3, Sihor Ghogha Rd | B584 | 20-Jan-24
                Oif Add- 6-16 Radhe Shaym Complex Waghawadi Rd, | B584 Within 10 Days
                GSTIN/UIN: 24AAEFR7351M1ZW
                Consignee (Ship to) Directorate Of Purchase And Stores Dispatched through Destination
                Department of Atomic Energy- KOTA By Aman Roadlines Kota
                NFC KOTA PLANT SITE, RAWATBHATTA,
                PO, ANUSHAKTI (VIA) KOTA Bill of Lading/LR-RR No. Motor Vehicle No.
                CHITTORGARH, RAJASTHAN-323303, India 81805 dt. 20-Jan-24 GJ19Y0109
                GSTIN/UIN : OBAAAGN1030Q1Z8
                Buyer (Bill to)
                Department of Atomic Energy- KOTA
                Directorate Of Purchase And Stores
                NFC KOTA PLANT SITE, RAWATBHATTA,
                PO, ANUSHAKTI (VIA) KOTA
                CHITTORGARH, RAJASTHAN-323303, India
                GSTIN/UIN : OBAAAGN1030Q1Z8
                Description of Goods HSN/SAC Quantity Rate per Amount
                I G ST Output- 18% 18 % 46,922.49
                Total 1.000 set 3,07,603.00
                Indian Rupees Three Lakh Seven Thousand Six Hundred Three Only
                HSN/SAC Taxable Value Rate Tax Amount Total Tax Amount
                73079190 Total 2,60,680.51 18% 46,922.49 46,922.49
                Company's Bank Details
                Bank Name : State Bank of India- 56007241003
                """;
    }

    private String screenshotStyleOcr() {
        return """
                TAX INVOICE(Page 2)
                (ORIGINAL FOR RECIPIENT)
                Tnvoice NO.
                eWay BiiN Dated
                Ranco Industries (2022-2023)
                20-Jan-24
                FacAd-S Not, Plot NoA Stor Ghana Re | B584
                Delivery Nate
                iMode/Terms of Payment
                Of Ad-G-16Rdhe Sham Compl Voghavad RE. BSB4
                Within 10 Days
                Near Racha Mandir Bhavnagar-364001
                Other References
                Mob No-9619377072/9825083030
                GSTINIUIN: 24AAEFR7351M1ZW
                584 dt. 20-Jan-24
                Buyer's Order No:
                Dated
                State Name : Gujarat, Code : 24
                GEMC-511687754738820
                21-Dec-23
                Gonsignee (Ship to)
                Dispatch Doc No.
                Delivery Note Date
                B584
                20-Jan-24
                Department of Atomic Energy- KOTA.
                Dispatched through
                Destination
                Directorate Of Purchase And Stores
                Kota
                NEC KOTA PLANT SITE, RAWATBHATTA,
                By Aman Roadlines
                PO, ANUSHAKTI (VIA) KOTA
                Bill of Lading/LR-RR No.
                Motor Vehicle No,
                CHITTORGARH, RAJASTHAN-323303, India
                81805 dt. 20-Jan-24
                Gs1gv0109
                GSTINUIN
                1 OBAAAGN10300128
                Terms of Delivery
                State Name : Rajasthan, Code : 08
                Ex Work
                Buyer (Billo)
                Department of Atomic Energy- KOTA ane
                Directorate Of Purchase And Stores
                INFC KOTA PLANT SITE, RAWATBHATTA,
                PO, ANUSHAKTI (VIA) KOTA
                ICHITTORGARH, RAJASTHAN-323303, India
                GSTINUIN
                OBAAAGN10300128
                Description of Goods
                HSNSAC Guantiy Rate Per Amount
                1G ST Output- 18%
                18%
                46,922.49
                Teal
                7.000 set
                3,07, 603.00
                Amount Chargeable fr wor)
                Indian Rupees Three Lakh Seven Thousand Six Hundred Three Only
                FSNISAC
                Taxable
                1ST
                Total
                Value
                Rate:
                Amount
                73078790
                0,680.57
                46,922.45
                Tax Amount
                46.9
                39
                Total 260,680.51 18% 46,922.49 46,922.49
                Tax Amount (in words)
                Indian Rupees Forty Six Thousand Nine Hundred Twenty Two and Forty Nine
                paise Only
                Company's Bank Details
                Bank Name
                State Bank of India- 56007241003
                """;
    }

    private String buyerZoneDispatchNoiseOcr() {
        return """
                ACME INDUSTRIES PRIVATE LIMITED
                GSTIN/UIN : 29ABCDE1234F1Z5
                Tax Invoice
                Invoice No : INV1002
                Date : 02-Apr-2026
                Consignee (Ship To) Dispatch Doc No : GEMC511687754738820
                Transport GSTIN/UIN : 24AAAAA0000A1Z5
                OMEGA TRADERS LLP
                Warehouse 7, Market Road
                GSTIN/UIN : 07LMNOP4321Q1Z8
                Description Qty Rate Amount
                Service Charges 1 1000 1000
                Grand Total 1000
                Amount Payable 1000
                """;
    }

    private String multilineLineItemsOcr() {
        return """
                ACME INDUSTRIES PRIVATE LIMITED
                GSTIN: 29ABCDE1234F1Z5
                Tax Invoice
                Invoice No: INV1001
                Date: 01-Apr-2026
                Billed To
                OMEGA TRADERS LLP
                GSTIN: 27PQRSX6789L1Z2
                Description of Goods HSN/SAC Qty Rate Amount
                Premium Stainless
                Steel Storage Cabinet 94032010 2 12500 25000
                Industrial Workbench 94038900 1 18000 18000
                Subtotal 43000
                CGST 9% 3870
                SGST 9% 3870
                Grand Total 50740
                Amount Payable 50740
                """;
    }
}
