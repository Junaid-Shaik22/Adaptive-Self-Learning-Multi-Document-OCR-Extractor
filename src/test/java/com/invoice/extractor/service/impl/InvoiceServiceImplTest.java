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

        assertNotNull(data.getTemplateId());
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

    @Test
    void extractsParthStyleInvoiceWithoutGarbageItemsOrWrongTotals() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, parthStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("107", data.getInvoiceNumber());
        assertEquals("30-01-2024", data.getInvoiceDate());
        assertEquals("PARTH ENERGY SYSTEMS.PVT. LTD", data.getVendorName());
        assertEquals("08AAECP5414C1Z2", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("Sr. Manager Materials"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
        assertEquals("39905.08", data.getSubTotal());
        assertEquals("7182.92", data.getTaxAmount());
        assertEquals("47088", data.getTotalAmount());
        assertEquals(1, data.getLineItems().size());
        assertEquals("8467", data.getLineItems().get(0).getHsn());
        assertEquals("4", data.getLineItems().get(0).getQuantity());
        assertEquals("9976.27", data.getLineItems().get(0).getUnitPrice());
        assertEquals("39905.08", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsMysoreStyleInvoiceAndRepairsVendorGstin() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, mysoreStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("GST2324/2808", data.getInvoiceNumber());
        assertEquals("6-Feb-24", data.getInvoiceDate());
        assertEquals("Mysore Ammonia and Chemicals Limited", data.getVendorName());
        assertEquals("36AABCC0037H1Z0", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("Stores Officer/Asst. Stores Officer"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
        assertEquals("190000", data.getSubTotal());
        assertEquals("34200", data.getTaxAmount());
        assertEquals("224200", data.getTotalAmount());
        assertEquals(1, data.getLineItems().size());
        assertEquals("28141000", data.getLineItems().get(0).getHsn());
        assertEquals("2500", data.getLineItems().get(0).getQuantity());
        assertEquals("76", data.getLineItems().get(0).getUnitPrice());
        assertEquals("190000", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsMadhavFoamStyleInvoiceWithBuyerGstinAndInvoiceAmount() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, madhavFoamStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("MPE/23-24/01191", data.getInvoiceNumber());
        assertEquals("20/01/2024", data.getInvoiceDate());
        assertEquals("MADHAV PE FOAM PRIVATE LTD", data.getVendorName());
        assertEquals("06AAMCM3562G1Z0", data.getVendorGstin());
        assertTrue(data.getBuyerName().toUpperCase().contains("NUCLEAR FUEL COMPLEX"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
        assertEquals("284746", data.getSubTotal());
        assertEquals("51254.28", data.getTaxAmount());
        assertEquals("336000", data.getTotalAmount());
        assertEquals(1, data.getLineItems().size());
        assertEquals("94042190", data.getLineItems().get(0).getHsn());
        assertEquals("70", data.getLineItems().get(0).getQuantity());
        assertEquals("4067.8", data.getLineItems().get(0).getUnitPrice());
        assertEquals("284746", data.getLineItems().get(0).getAmount());
    }

    @Test
    void reusesSingleTemplateIdAcrossOcrVariantsOfSameLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl firstService = buildService(templatePath, noisyRancoOcr());
        InvoiceData first = firstService.processInvoice(new MockMultipartFile("file", "one.png", "image/png", new byte[0]));

        InvoiceServiceImpl secondService = buildService(templatePath, screenshotStyleOcr());
        InvoiceData second = secondService.processInvoice(new MockMultipartFile("file", "two.png", "image/png", new byte[0]));

        List<Template> templates = new JsonTemplateRepository(templatePath).loadTemplates();
        assertNotNull(first.getTemplateId());
        assertEquals(first.getTemplateId(), second.getTemplateId());
        assertEquals(1, templates.size());
    }

    @Test
    void extractsIdentityFieldsFromAuditStyleHpCorporationLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, hpCorporationStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("H.P.Corporation", data.getVendorName());
        assertEquals("09CJFPS0084Q1ZG", data.getVendorGstin());
        assertNotNull(data.getBuyerName());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy"));
        assertFalse(data.getBuyerName().contains("GEMC"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
    }

    @Test
    void extractsIdentityFieldsFromAuditStyleControlsoftLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, controlsoftStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("Controlsoft Engineering India Pvt Ltd", data.getVendorName());
        assertEquals("33AABCC8871D1ZT", data.getVendorGstin());
        assertNotNull(data.getBuyerName());
        assertTrue(data.getBuyerName().contains("NUCLEAR FUEL COMPLEX"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
    }

    @Test
    void extractsIdentityFieldsFromAuditStyleAscencionLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, ascencionStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertTrue(data.getVendorName().contains("ASCENCION ELECTRONICS"));
        assertEquals("07CXGPS0971P1ZP", data.getVendorGstin());
        assertNotNull(data.getBuyerName());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy Stores"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
    }

    @Test
    void extractsIdentityFieldsFromAuditStyleSouthIndiaBearingLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, southIndiaBearingStyleOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertTrue(data.getVendorName().contains("SOUTH INDIA BEARING CO."));
        assertEquals("33ABJFS7716D1Z7", data.getVendorGstin());
        assertNotNull(data.getBuyerName());
        assertTrue(data.getBuyerName().contains("HYDERABAD REGIONAL PURCHASE UNIT"));
        assertFalse(data.getBuyerName().toLowerCase().contains("invoice detals"));
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

    private String parthStyleOcr() {
        return """
                PARTH ENERGY SYSTEMS.PVT. LTD
                GST NO. 08AAECP5414C1Z2
                TAX INVOICE
                Invoice No. : 107
                Date : 30-01-2024
                Purchase Order Dated : 13-01-2024
                Billed To
                Name Sr. Manager Materials
                Address Department of Atomic Energy, HRPSU, NFC, P.O. ECIL, HYDERABAD, TELANGANA-500062, India
                GST IN / 36AAAGN1030Q1Z9
                Ship to
                Name Sr. Manager Materials
                Address Department of Atomic Energy, HRPSU, NFC, P.O. ECIL, HYDERABAD, TELANGANA-500062, India
                GST IN / 36AAAGN1030Q1Z9
                Name of Good HSN CODE Qty UOM Rate Amount Discount Taxable Value
                BOSCH make cordless Drilling machine,
                Model Number GSR185 Li professional 8467 4 Nos 9976.27 39905.08 0.00 39905.08
                Total 39905.08 0.00 39905.08
                Sub Total 39905.08
                Add : IGST 18% 7182.92
                Total Amount After Tax 47088.00
                """;
    }

    private String mysoreStyleOcr() {
        return """
                Tax Invoice
                Mysore Ammonia and Chemicals Limited
                GSTIN No. : 36AABCC0037HIZO
                Invoice No : GST2324/2808
                Invoice Date : 6-Feb-24
                Details of Recipient (Billed to)
                M/s. The Stores Officer/Asst. Stores Officer
                Directorate of Purchase & Stores, Hyderabad Regional Stores Unit,
                Nuclear Fuel Complex, ECIL (PO), Hyderabad,
                Pin Code : 500062
                GSTIN No. : 36AAAGN1030Q1Z9
                Details of Consignee (Shipped to)
                M/s.The Stores Officer/Asst. Stores Officer
                Directorate of Purchase & Stores, Hyderabad Regional Stores Unit,
                Nuclear Fuel Complex, ECIL (PO), Hyderabad,
                Pin Code : 500062
                GSTIN No. : 36AAAGN1030Q1Z9
                Description of Goods/Services HSN/SAC Code No.of Pkgs Qty Rate UOM Taxable Value GST % GST Amount
                Anhydrous (liquid) Ammonia / Ammonia Gas 28141000 5X500 KG 2500.000 76.00 kgs 190000.00 18.00 34200.00
                TOTAL 34200.00
                CGST 9% Sales 17100.00
                SGST 9% Sales 17100.00
                Total Invoice Value (figure): 224200.00
                Total Invoice Value (Words): Indian Rupees Two Lakh Twenty Four Thousand Two Hundred Only
                """;
    }

    private String madhavFoamStyleOcr() {
        return """
                GSTIN : O6AAMCM3562G12D GST INVOICE Original For Buyer
                MADHAV PE FOAM PRIVATE LTD
                AN ISO 9001 : 2008 CERTIFIED COMPANY
                Invoice No MPE/23-24/01191 Date: 20/01/2024
                Details of Receiver (Billed to) Details of Consignee (Shipped to) BANK Details
                NUCLEAR FUEL COMPLEX
                Nuclear Fuel Complex Aadhar Building
                3rd Floor ECIL Post
                Hyderabad - 500062
                STATE : TELANGANA CODE: 36
                GSTIN: 36AAAGN10309129 PAN: AAAGN1030Q
                SN DESCRIPITION OF GOODS H.S.N PKG QTY UNT RATE SALE AMT IG8T% DIS%
                1 TARANG MATTRESS 79*36 x 76.0x 94042190 1 70.00 Pcs 4067.800 284746,.00 18.00
                TOTAL 1 70.00 284746.00
                TAXABLE AMT 284746.00
                I.G.S.T 51254.28
                Inv Value (In Fig): 336000.00
                INVOICE AMT 336000.00
                H.S.N GST % PKG QTY AMOUNT I.GST
                94042190 18.00 1 70.00 284746.00 51254.28
                """;
    }

    private String hpCorporationStyleOcr() {
        return """
                Tax Invoice
                H.P.Corporation Invoice No. 99 Dated 14-Nov-2023
                GSTIN/UIN: O9CJFPS0084Q1ZG
                Buyer Department of Atomic Energy GEMC-511687783218793
                Directorate Of Purchase And Stores Dispatch Document No. Delivery Note Date
                HRPSU, NFC, P.O. ECIL,, HYDERABAD,
                TELANGANA-500062, India
                GSTIN:- 36AAAGN1030Q1Z9 Terms of Delivery
                Description of Goods HSN/SAC Quantity Rate per Amount
                """;
    }

    private String controlsoftStyleOcr() {
        return """
                Tax Invoice          (ORIGINAL FOR RECIPIENT)              e-Invoice
                Controlsoft Engineering India Pvt Ltd Invoice No. 142/23-24 Dated 30Jan-24
                GSTIN/UIN: 33AABCC8871D1ZT
                Buyer (Bill to)
                NUCLEAR FUEL COMPLEX
                Aadhar Building, 3rd Floor,
                ECIL Post, Hyderabad, Telangana, 500062.
                GSTIN/UIN : 36AAAGN1030Q1Z9
                Description of Services HSN/SAC Amount
                """;
    }

    private String ascencionStyleOcr() {
        return """
                TAX INVOICE
                ASCENCION ELECTRONICS -
                GSTIN : O7CXGPS0971P1ZP
                Billed to :
                Department of Atomic Energy Stores
                Stores Officer
                HRPSU, NFC, P.O. ECIL,,
                HYDERABAD, TELANGANA-500062,
                GSTIN / UIN : 36AAAGN1030Q1Z9
                Description of Goods HSN/SAC Qty Unit Price Amount
                """;
    }

    private String southIndiaBearingStyleOcr() {
        return """
                Tax Invoice
                SOUTH INDIA BEARING CO. - (2023-2024) Invoice No. SIB/4142/23-24 Dated 18-Dec-23
                GSTIN/UIN: 33ABJFS7716D1Z7
                Consignee (Ship to)
                STORES OFFICER/ ASST. STORES OFFICER
                DIRECTORATE OF PURCHASE & STORES
                HYDERABAD REGIONAL STORES UNIT,
                NUCLEAR FUEL COMPLEX,
                ECIL POST,
                SeTvUI / AARGNE 030Q1Z9
                Buyer (Bill to)
                HYDERABAD REGIONAL PURCHASE UNIT
                DEPARTMENT OF ATOMIC ENERGY
                DIRECTORATE OF PURCHASE & STORES
                NEC, PO, EGIL
                HYDERABAD - 500062
                GSTIN/UIN | AOAAAGNIOS0Q41 20
                Description of Goods HSN/SAC GST Quantity Rate per Amount
                """;
    }
}
