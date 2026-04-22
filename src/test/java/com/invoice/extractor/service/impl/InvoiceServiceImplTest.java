package com.invoice.extractor.service.impl;

import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.model.InvoiceOcrDocument;
import com.invoice.extractor.model.InvoiceOcrPage;
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
        assertEquals("NOT_MENTIONED", data.getOrderReference());
        assertEquals("NOT_MENTIONED", data.getDeliveryNote());
        assertNotNull(data.getLineItems());
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
        assertNotNull(data.getLineItems());
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
        assertNotNull(data.getLineItems());
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
    void extractsHeaderlessLowValueLineItemsFromConsecutiveRows() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, headerlessLowValueLineItemsOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertNotNull(data.getLineItems());
        assertEquals(2, data.getLineItems().size());
        assertEquals("Widget A", data.getLineItems().get(0).getDescription());
        assertEquals("1001", data.getLineItems().get(0).getHsn());
        assertEquals("2", data.getLineItems().get(0).getQuantity());
        assertEquals("50", data.getLineItems().get(0).getUnitPrice());
        assertEquals("100", data.getLineItems().get(0).getAmount());
        assertEquals("Widget B", data.getLineItems().get(1).getDescription());
        assertEquals("1002", data.getLineItems().get(1).getHsn());
        assertEquals("3", data.getLineItems().get(1).getQuantity());
        assertEquals("20", data.getLineItems().get(1).getUnitPrice());
        assertEquals("60", data.getLineItems().get(1).getAmount());
        assertEquals("160", data.getSubTotal());
        assertEquals("160", data.getTotalAmount());
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
        assertTrue(data.getBuyerName().contains("Stores Officer/Asst. Stores Officer"));
        assertEquals("Telangana", data.getState());
        assertEquals("500062", data.getPincode());
        assertEquals(1, data.getLineItems().size());
        assertEquals("28141000", data.getLineItems().get(0).getHsn());
        assertEquals("2500", data.getLineItems().get(0).getQuantity());
        assertEquals("76", data.getLineItems().get(0).getUnitPrice());
        assertEquals("190000", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsLineItemsFromAuditOcrMysoreLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditMysoreActualOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("GST2324/2808", data.getInvoiceNumber());
        assertEquals(1, data.getLineItems().size());
        assertTrue(data.getLineItems().get(0).getDescription().contains("Anhydrous"));
        assertEquals("28141000", data.getLineItems().get(0).getHsn());
        assertEquals("2500", data.getLineItems().get(0).getQuantity());
        assertEquals("76", data.getLineItems().get(0).getUnitPrice());
        assertEquals("190000", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsLineItemsFromAuditOcrParthLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditParthActualOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("107", data.getInvoiceNumber());
        assertEquals(1, data.getLineItems().size());
        assertTrue(data.getLineItems().get(0).getDescription().contains("BOSCH make cordless Drilling machine"));
        assertEquals("8467", data.getLineItems().get(0).getHsn());
        assertEquals("4", data.getLineItems().get(0).getQuantity());
        assertEquals("9976.27", data.getLineItems().get(0).getUnitPrice());
        assertEquals("39905.08", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsLineItemsFromAuditOcrAvTradingLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditAvTradingActualOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("AV/23-24/265", data.getInvoiceNumber());
        assertEquals(1, data.getLineItems().size());
        assertTrue(data.getLineItems().get(0).getDescription().contains("CUTTING WHEEL"));
        assertEquals("6804", data.getLineItems().get(0).getHsn());
        assertEquals("180", data.getLineItems().get(0).getQuantity());
        assertEquals("38.14", data.getLineItems().get(0).getUnitPrice());
        assertEquals("8100", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsLineItemsFromAuditOcrMadhavLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditMadhavActualOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("MPE/23-24/01191", data.getInvoiceNumber());
        assertEquals(1, data.getLineItems().size());
        assertTrue(data.getLineItems().get(0).getDescription().contains("TARANG MATTRESS"));
        assertEquals("94042190", data.getLineItems().get(0).getHsn());
        assertEquals("70", data.getLineItems().get(0).getQuantity());
        assertEquals("4067.8", data.getLineItems().get(0).getUnitPrice());
        assertEquals("284746", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsLineItemsFromAuditOcrAscencionLayout() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditAscencionActualOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("295", data.getInvoiceNumber());
        assertEquals(1, data.getLineItems().size());
        assertTrue(data.getLineItems().get(0).getDescription().contains("Weller 200 Deg C"));
        assertEquals("8515", data.getLineItems().get(0).getHsn());
        assertEquals("1", data.getLineItems().get(0).getQuantity());
        assertEquals("13559.31", data.getLineItems().get(0).getUnitPrice());
        assertEquals("13559.31", data.getLineItems().get(0).getAmount());
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
        assertEquals("Telangana", data.getState());
        assertEquals("Telangana", data.getPlaceOfSupply());
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
    void extractsAuditStyleControlsoftNoiseLineItemAndNames() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, controlsoftAuditNoiseOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("Controlsoft Engineering India Pvt Ltd", data.getVendorName());
        assertEquals("33AABCC8871D1ZT", data.getVendorGstin());
        assertTrue(data.getBuyerName().contains("NUCLEAR FUEL COMPLEX"));
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
        assertNotNull(data.getLineItems());
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
    void extractsAuditStyleAvTradingNamesAndLineItem() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, avTradingAuditNoiseOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("AV TRADING COMPANY", data.getVendorName());
        assertEquals("27BLPPS1385F1ZM", data.getVendorGstin());
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
        assertEquals(1, data.getLineItems().size());
        assertEquals("6804", data.getLineItems().get(0).getHsn());
        assertEquals("8100", data.getLineItems().get(0).getAmount());
    }

    @Test
    void extractsAuditStyleIrelBuyerAndLineItem() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, irelAuditNoiseOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("IREL (India) Limited", data.getVendorName());
        assertTrue(data.getBuyerName().contains("NUCLEAR FUEL COMPLEX"));
        assertEquals(1, data.getLineItems().size());
        assertEquals("26151000", data.getLineItems().get(0).getHsn());
        assertEquals("15", data.getLineItems().get(0).getQuantity());
        assertEquals("181750", data.getLineItems().get(0).getUnitPrice());
        assertEquals("2726250", data.getLineItems().get(0).getAmount());
    }

    @Test
    void cleansAuditStyleMysoreNoiseWithoutKeepingBadTransportValues() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditStyleMysoreNoiseOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertTrue(data.getBuyerName().contains("Stores Officer/Asst. Stores Officer"));
        assertEquals("ECIL", data.getDestination());
        assertEquals("HDFC BANK LIMITED", data.getBankName());
        assertEquals("50200059361983", data.getAccountNumber());
        assertEquals("NOT_MENTIONED", data.getTransporterName());
        assertEquals("NOT_MENTIONED", data.getTransportDetails());
    }

    @Test
    void extractsWebsiteAndEmailFromAuditStyleContactLinesWithoutInventingBankData() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, auditStyleAscencionContactOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("D70C", data.getInvoiceNumber());
        assertEquals("07CXGPS0971P1ZP", data.getVendorGstin());
        assertEquals("sales@ascencionelectronics.com", data.getVendorEmail());
        assertEquals("www.ascencionelectronics.com", data.getVendorWebsite());
        assertEquals("NOT_MENTIONED", data.getBankName());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy Stores"));
        assertEquals("DTOC COURIER", data.getTransportDetails());
        assertEquals("Telangana", data.getPlaceOfSupply());
    }

    @Test
    void extractsRawAuditRancoPageWithCorrectAmountsAndBankFields() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, rawAuditRancoPageOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("B584", data.getInvoiceNumber());
        assertEquals("20-Jan-24", data.getInvoiceDate());
        assertEquals("24AAEFR7351M1ZW", data.getVendorGstin());
        assertEquals("08AAAGN1030Q1Z8", data.getBuyerGstin());
        assertEquals("State Bank of India", data.getBankName());
        assertEquals("56007241003", data.getAccountNumber());
        assertEquals("SBIN0063762", data.getIfscCode());
        assertEquals("260680.51", data.getSubTotal());
        assertEquals("46922.49", data.getTaxAmount());
        assertEquals("307603", data.getTotalAmount());
    }

    @Test
    void extractsRawAuditMadhavPageWithNamedBankAndTransporter() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, rawAuditMadhavPageOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("MPE/23-24/01191", data.getInvoiceNumber());
        assertEquals("ICICI BANK", data.getBankName());
        assertEquals("662805600466", data.getAccountNumber());
        assertEquals("ICIC0006628", data.getIfscCode());
        assertEquals("DELHI M.P. ROADLINE", data.getTransporterName());
        assertEquals("DELHI M.P. ROADLINE", data.getTransportDetails());
        assertEquals("284746", data.getSubTotal());
        assertEquals("51254.28", data.getTaxAmount());
        assertEquals("336000", data.getTotalAmount());
    }

    @Test
    void extractsRawAuditAscencionPageWithBankAndTaxBreakdown() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, rawAuditAscencionPageOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("295", data.getInvoiceNumber());
        assertEquals("07CXGPS0971P1ZP", data.getVendorGstin());
        assertEquals("36AAAGN1030Q1Z9", data.getBuyerGstin());
        assertNotEquals("NOT_MENTIONED", data.getBuyerName());
        assertEquals("AXIS BANK", data.getBankName());
        assertEquals("916020036468661", data.getAccountNumber());
        assertEquals("UTIB0000786", data.getIfscCode());
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

    @Test
    void extractsUniversalInvoiceBusinessAndMetadataFields() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, supplementalFieldsOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("PO-7788/24-25", data.getPoNumber());
        assertEquals("05-Apr-2026", data.getPoDate());
        assertEquals("RFQ-5566/24", data.getOrderReference());
        assertEquals("DN-4455", data.getDeliveryNote());
        assertEquals("FASTTRACK LOGISTICS LLP", data.getDispatchThrough());
        assertEquals("FASTTRACK LOGISTICS LLP", data.getTransporterName());
        assertEquals("FASTTRACK LOGISTICS LLP", data.getTransportDetails());
        assertEquals("TS09AB1234", data.getVehicleNumber());
        assertEquals("Hyderabad", data.getDestination());
        assertEquals("Telangana", data.getPlaceOfSupply());
        assertEquals("30 DAYS CREDIT", data.getPaymentTerms());
        assertEquals("HDFC BANK", data.getBankName());
        assertEquals("9988776655", data.getAccountNumber());
        assertEquals("HDFC0000123", data.getIfscCode());
        assertEquals("Banjara Hills", data.getBranch());
        assertEquals("IRN998877665544332211", data.getIrn());
        assertEquals("ACK/2026/001", data.getAckNumber());
        assertEquals("EWB123456789", data.getEwayBill());
        assertEquals("+91 9876543210, 04012345678", data.getVendorPhone());
        assertEquals("sales@acmeprocess.com", data.getVendorEmail());
        assertEquals("acmeprocess.com", data.getVendorWebsite());
        assertTrue(data.getVendorAddress().contains("Plot 44 Industrial Estate"));
        assertTrue(data.getBuyerAddress().contains("Aadhar Building"));
        assertEquals("ABCDE1234F", data.getVendorPAN());
        assertEquals("U12345TG2010PTC123456", data.getVendorCIN());
        assertEquals("UDYAM-TG-12-1234567", data.getMsmeNumber());
        assertEquals("Telangana", data.getState());
        assertEquals("36", data.getStateCode());
        assertEquals("500062", data.getPincode());
        assertEquals("15000", data.getTaxableValue());
        assertEquals("1350", data.getCgst());
        assertEquals("1350", data.getSgst());
        assertEquals("NOT_MENTIONED", data.getIgst());
        assertEquals("0", data.getRoundOff());
        assertNull(data.getDynamicFields());
        assertNull(data.getKnownFields());
        assertNull(data.getRawText());
    }

    @Test
    void keepsPoAndVehicleOutOfBuyerAddressUsingPriorityRedaction() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, poOverlapAddressOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("GEMC-12345", data.getPoNumber());
        assertEquals("TS09AB1234", data.getVehicleNumber());
        assertEquals("500062", data.getPincode());
        assertTrue(data.getBuyerName().contains("Department of Atomic Energy Stores"));
        assertTrue(data.getBuyerAddress().contains("HRPSU, NFC"));
        assertFalse(data.getBuyerAddress().contains("GEMC-12345"));
        assertFalse(data.getBuyerAddress().toLowerCase().contains("invoice no"));
        assertFalse(data.getBuyerAddress().toLowerCase().contains("gstin"));
    }

    @Test
    void reconstructsSubtotalAndTaxFromSparseSummaryWhenOnlyTotalIsLabeled() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, sparseFinancialSummaryOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("INV9001", data.getInvoiceNumber());
        assertEquals("10000", data.getSubTotal());
        assertEquals("1800", data.getTaxAmount());
        assertEquals("11800", data.getTotalAmount());
    }

    @Test
    void prefersFirstPageHeaderLastPageTotalsAndMiddlePageTablesForMultiPageInvoices() {
        Path templatePath = tempDir.resolve("templates.json");
        OcrService ocrService = new OcrService() {
            @Override
            public String extractText(org.springframework.web.multipart.MultipartFile file) {
                return "";
            }

            @Override
            public InvoiceOcrDocument extractDocument(org.springframework.web.multipart.MultipartFile file) {
                return new InvoiceOcrDocument(List.of(
                        new InvoiceOcrPage(1, "page-1.png", multiPageFirstPage()),
                        new InvoiceOcrPage(2, "page-2.png", multiPageMiddlePage()),
                        new InvoiceOcrPage(3, "page-3.png", multiPageLastPage())
                ));
            }
        };
        InvoiceServiceImpl service = buildService(templatePath, ocrService);

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[0]));

        assertEquals(3, data.getPagesProcessed());
        assertEquals("INV/24-25/0099", data.getInvoiceNumber());
        assertEquals("05-Apr-2026", data.getInvoiceDate());
        assertEquals("ACME PROCESS SYSTEMS PRIVATE LIMITED", data.getVendorName());
        assertEquals("29ABCDE1234F1Z5", data.getVendorGstin());
        assertEquals("OMEGA PROJECTS LLP", data.getBuyerName());
        assertEquals("27PQRSX6789L1Z2", data.getBuyerGstin());
        assertEquals("40000", data.getSubTotal());
        assertEquals("40000", data.getTaxableValue());
        assertEquals("3600", data.getCgst());
        assertEquals("3600", data.getSgst());
        assertEquals("7200", data.getTaxAmount());
        assertEquals("47200", data.getTotalAmount());
        assertNotNull(data.getLineItems());
        assertFalse(data.getLineItems().isEmpty());
        assertNull(data.getRawText());
        assertEquals("30 DAYS", data.getPaymentTerms());
        assertEquals("HDFC BANK", data.getBankName());
        assertEquals("9988776655", data.getAccountNumber());
        assertEquals("HDFC0000123", data.getIfscCode());
    }

    @Test
    void extractsAnchoredShortNumericInvoiceNumbersWithoutColumnBleed() {
        Path templatePath = tempDir.resolve("templates.json");
        InvoiceServiceImpl service = buildService(templatePath, shortNumericColumnSplitOcr());

        InvoiceData data = service.processInvoice(new MockMultipartFile("file", "invoice.png", "image/png", new byte[0]));

        assertEquals("7", data.getInvoiceNumber());
        assertEquals("01.04.2026", data.getInvoiceDate());
        assertEquals("ACME INDUSTRIES PRIVATE LIMITED", data.getVendorName());
        assertEquals("29ABCDE1234F1Z5", data.getVendorGstin());
        assertEquals("OMEGA PROJECTS LLP", data.getBuyerName());
        assertEquals("27PQRSX6789L1Z2", data.getBuyerGstin());
        assertEquals("GEMC-12345", data.getPoNumber());
        assertEquals("TS09AB1234", data.getVehicleNumber());
        assertFalse(data.getBuyerName().contains("GEMC"));
        assertFalse(data.getBuyerName().contains("TS09"));
        assertEquals("5000", data.getSubTotal());
        assertEquals("900", data.getTaxAmount());
        assertEquals("5900", data.getTotalAmount());
    }

    private InvoiceServiceImpl buildService(Path templatePath, String ocrText) {
        return buildService(templatePath, file -> ocrText);
    }

    private InvoiceServiceImpl buildService(Path templatePath, OcrService ocrService) {
        JsonTemplateRepository repository = new JsonTemplateRepository(templatePath);
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

    private String headerlessLowValueLineItemsOcr() {
        return """
                ACME INDUSTRIES PRIVATE LIMITED
                GSTIN: 29ABCDE1234F1Z5
                Tax Invoice
                Invoice No: INV1042
                Date: 21-Apr-2026
                Billed To
                OMEGA TRADERS LLP
                GSTIN: 27PQRSX6789L1Z2
                Widget A 1001 2 50 100
                Widget B 1002 3 20 60
                Subtotal 160
                Grand Total 160
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

    private String auditStyleMysoreNoiseOcr() {
        return """
                Tax Invoice
                Mysore Ammonia and Chemicals Limited
                GSTIN No. : 36AABCC9037H1ZN
                Invoice No : GST2324/2808
                Invoice Date : 6-Feb-24
                Details of Recipient (Billed to)
                ( Shipped to), M/s. The Stores Officer/Asst. Stores Officer
                Directorate of Purchase & Stores, Hyderabad Regional Stores Unit,
                Nuclear Fuel Complex, ECIL (PO), Hyderabad,
                Pin Code : 500062
                GSTIN No. : 36AAAGN1030Q1Z9
                Place of Supply : Telangana
                Destination : > ECIL
                Vehicle Number : AP28TD2823
                Transporter Name : Place of Supply : Telangana
                Transport Details : ed at full risk and liability of the Customer /
                HDFC BANK LIMITED CAPACITY. A/c Type : Current A/c A/c No - 50200059361983
                Taxable Value 190000.00
                CGST 17100.00
                SGST 17100.00
                Total Invoice Value (figure): 224200.00
                """;
    }

    private String auditStyleAscencionContactOcr() {
        return """
                TAX INVOICE
                Invoice No : D70C
                Date : 19-12-2023
                ASCENCION ELECTRONICS
                A-75, 2ND FLOOR, OPP PILI KOTHI, HARI NAGAR, NEW DELHI-110064, www.ascencionelectronics.com, Email-sales@ascencionelectronics.com
                Tel. : 9810927895
                GSTIN : O7CXGPS0971P1ZP
                Shipped to :
                Department of Atomic Energy Stores
                Stores Officer
                HRPSU, NFC, P.O. ECIL, HYDERABAD, TELANGANA-500062
                GSTIN / UIN : 36AAAGN1030Q1Z9
                Transport : DTOC COURIER
                Place of Supply : Telangana (36)
                Taxable Value 15999.99
                Grand Total 15999.99
                """;
    }

    private String rawAuditRancoPageOcr() {
        return """
                TAX INVOICE(Page 2) | (ORIGINAL FOR RECIPIENT)
                1 TM | Ranco Industries (2022-2023) | Invoice No. -_e-Way Bill No. | Dated
                Fact Aad : - S . No-150, Plot No-3A,Sihor Ghangali Rd |B584 | 20-Jan-24
                ANCO | Sihor-Bhavnagar, Gujarat-364240 | Delivery Note | Mode/Terms of Payment
                Off Add : - G-16,Radhe Shaym Complex, Waghawadi Rd. | B584 | Within 10 Days
                Near Radha Mandir, Bhavnagar-364001 | Reference No. & Date. | Other References
                Mob No-9619377072/9825083030 | asg4 gt 20-Jan-24 | Nil
                GSTIN/UIN : 24AAEFR7351M1ZW
                State N | Gujarat. Code : 24 | Buyer's Order No. | Dated
                _ | . | E-Mail : mum@indiaflanges.com | eee o1-Dec-e3 Dal
                Gonsignee (Ship to) | ispatch Doc No. | elivery Note Date
                Department of Atomic Energy- KOTA | B54 BDU | 20-Jan-24
                Directorate Of Purchase And Stores | ispatcne | ong | esnnalion
                NEC KOTA PLANT SITE, RAWATBHATTA, | By Aman Roadliines | Kota
                PO, ANUSHAKTI (VIA) KOTA | Bill of Lading/LR-RR No. [Motor Vehicle No.
                CHITTORGARH, RAJASTHAN-323303, India | 81805 dt. 20-Jan-24 | GJ19Y0109
                GSTIN/UIN | > OBAAAGN1030Q12Z8 | Terms of Delivery
                State Name | ; Rajasthan, Code : 08 | Ex Work
                Buyer (Bill to)
                Department of Atomic Energy- KOTA
                Directorate Of Purchase And Stores
                NFC KOTA PLANT SITE, RAWATBHATTA,
                PO, ANUSHAKT] (VIA) KOTA
                CHITTORGARH, RAJASTHAN-323303, India
                GSTIN/UIN | - O8BAAAGN10300128
                Stale Name | : Rajasthan, Code : 08
                S| | Description of Goods | HSN/SAC | Quantity | Rate | per | Amount
                INC}
                | | 1G ST Output- 18% | 18|% | 46,922.49
                4 | 7 | Total | 1.000 set | % 3,07,603.00
                Amount Chargeable (in words)
                Indian Rupees Three Lakh Seven Thousand Six Hundred Three Only
                HSN/SAC | Taxable | IGST | Total
                _ | | Value | Rate : Amount | Tax Amount
                73079190 | oo | | 2,60,680.51) 18%] 46,922.49) 46,922.49
                oe | Total} 2,60,680.51 | 46,922.49} 46,922.49
                Company's Bank Details
                Bank Name | : State Bank of India- 56007241003
                ; | A/c No. | > 56007241003 (IFSC CODE-SBIN0063762)
                """;
    }

    private String rawAuditMadhavPageOcr() {
        return """
                GSTIN : O6AAMCM3562G12D | GST INVOICE | Original For Buyer
                MADHAV PE FOAM PRIVATE LTD
                AN ISO 9001 : 2008 CERTIFIED COMPANY
                Khasra No.7/24/1/2 ,4/1/2,7/1/1,7/1/2,7/2,8/1,8/2,14 17/1/1,Vi11. Thana Khurd, Kharkhoda. Sonipat-131402
                (M) 9811131220,9811421412. E-Mail : - madhavpefoam@gmail.com
                PAN.No : AAMCN3562G
                Invoice No MPE/23-24/01191 | Date : | 20/01/2024
                Details of Receiver (Billed to) | Details of Consignee (Shipped to) | BANK Details
                NUCLEAR FUEL COMPLEX | BANK tAME
                ICICI BANK
                ACCOUNT NO
                uclear Fuel Complex Aadhar Building
                3rd Fhoor ECIL Post
                Hyderabad - 500062
                662805600466
                IFSC CODE : ICIC0006628
                PHONE : 040-27183077, | BANK ADDRESS
                STATE ; TELANGANA | CODE : 36 | ; 132 , HARGOBIND
                GSTIN : 36AAAGN10309129 PAN : AAAGN1030Q
                SN DESCRIPITION OF GOODS | H.S.N PKG | QTY UNT | RATE | SALE AMT | IG8T% DIS%
                1] TARANG MATTRESS?79*36 | x 76.0x | 94042190 | 1 | 70.00 Pcs | 4067.800 | 264746,.00 | 18.00
                TOTAL | BO | 1 | 70,00 | 284746.00
                PCS : | 70 TOTAL : | 70.00 | TAXABLE AMT | 284746.00
                P.O.No.& Dt : | i, G,S.7T | 51254.28
                Vehicle No : HR38W8099 | Mode : CANTER | GR.NO : | 4599
                Transporter Nm. : DELHI M.P. ROADLINE
                Inv Value (In Fig) : | 336000.00
                Inv Value (In Words) : Rs.Three lacs thirty six thousand Only | ROUND OFF | -0.238
                INVOICE AMT | 336000.00
                """;
    }

    private String rawAuditAscencionPageOcr() {
        return """
                Onginal Cop We
                TAX INVOICE | " | * y | .
                ASCENCION ELECTRONICS -
                | aw
                A-75,2ND FLOOR,OPP PILI KOTHI, HARI NAGAR,NEW DELHI-110064
                www.ascencionelectronics.com, Email-sales@ascencionelectronics.com
                GSTIN : O7CXGPS0971P1ZP
                Tel. : 9810927895 email : accounts@ascencionelectronics.com
                MSME UDYAM REG NO. : DL-11-0016528
                Invoice No. | > 295 | GR/RR No.
                Dated | » 19-12-2023 | Transport | : DTOC COURIER
                | Place of Supply : Telangana (36) | Vehicle No. | |
                Reverse Charge | : N | Station | : Billed to : | Shipped to : | : Department of Atomic Energy Stores | Department of Atomic Energy Stores
                Stores Officer | * | Stores Officer | : HRPSU, NFC, P.O. ECIL,, | HRPSU, NFC, P.O. ECIL,,
                HYDERABAD, TELANGANA-500062, | i HYDERABAD, TELANGANA-500062,
                Party E-Mail ID | : sonfc@nfc.gov.in
                Party Mobile No : 040-27184406
                State | : Telangana (36)
                Party Pincode | : 500062
                GSTIN / UIN | 36AAAGN1030Q1Z9
                GOEMC-541687792633853 DATE-14.12.2023
                S.N. | Description of Goods | HSN/SAC | Qty. | Unit | Price | IGST | Amount
                Weller 200 Deg C to 450 Deg C 24 V Out | 8515 | 1.00 | Pcs. | 13,559.31 | 2,440.68 | 15,999.99
                Desoldering Station With Digital Display
                MODEL. WELOLO
                Grand Total | 15,999.99
                Tax Rate TaxableAmt. IGSTAmt. Total Tax
                18% | 13,559.31 2,440.68 | 2,440.68
                Bank Details | BANK : AXIS BANK IFSC CODE : UT1B0000786
                A/C NO : 916020036468661 A/C HOLDER'S NAME : ASCENCION ELECTRONICS
                Terms & Conditions
                Receiver's Signature
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

    private String supplementalFieldsOcr() {
        return """
                Tax Invoice
                ACME PROCESS SYSTEMS PRIVATE LIMITED
                Plot 44 Industrial Estate
                Hyderabad Telangana 500062
                GSTIN : 29ABCDE1234F1Z5
                PAN : ABCDE1234F
                CIN : U12345TG2010PTC123456
                MSME No : UDYAM-TG-12-1234567
                Phone : +91 9876543210 / 04012345678
                Email : sales@acmeprocess.com
                Website : www.acmeprocess.com
                Invoice No : INV/24-25/1001
                Date : 06-Apr-2026
                Buyer (Bill to)
                OMEGA PROJECTS LLP
                Aadhar Building, 3rd Floor, ECIL Post, Hyderabad 500062
                GSTIN : 36PQRSX6789L1Z2
                State : Telangana Code : 36
                PO Number : PO-7788/24-25
                Purchase Order Dated : 05-Apr-2026
                Order Reference : RFQ-5566/24
                Delivery Note : DN-4455
                Dispatched Through : FASTTRACK LOGISTICS LLP
                Vehicle Number : TS09AB1234
                Destination : Hyderabad
                Place of Supply : Telangana
                Terms of Payment : 30 DAYS CREDIT
                Bank Details : HDFC BANK | A/C NO 9988776655 | IFSC HDFC0000123 | Branch : Banjara Hills
                IRN : IRN998877665544332211
                Ack No : ACK/2026/001
                E-Way Bill No : EWB123456789
                Reference Code : REF-42
                Department : Projects
                Description Qty Rate Amount
                Control Panel 1 15000 15000
                Taxable Value 15000
                CGST 9% 1350
                SGST 9% 1350
                Round Off 0
                Grand Total 17700
                Amount Payable 17700
                """;
    }

    private String controlsoftAuditNoiseOcr() {
        return """
                Tax Invoice | (ORIGINAL FOR RECIPIENT) | e-Invoice
                IRN | : 21156e0e6db34dc0cc3fc0118065691f33a9b7ad4d8-
                Ack No. ; 162416936471993
                Ack Date : 30Jan-24
                Controlsoft Engineering India Pvt Ltd | Invoice No. | Dated
                No.534,1st Floor, East Coast Centre, |142/23-24 80San-24
                Anna Salai, Teynampet,
                Chennai - 600018
                GSTIN/UIN : 33AABCC8871D1ZT
                State Name : Tamil Nadu, Code : 33
                CIN : U74210TN2002PTC049562
                E-Mail : info@controlsoftengg.in
                Buyer (Bill to)
                NUCLEAR FUEL COMPLEX
                Aadhar Building, 3rd Floor,
                ECIL Post, Hyderabad, Telangana, 500062.
                GSTIN/UIN | : 36AAAGN1030Q1Z9
                State Name | : Telangana, Code : 36
                SI | Description of | Services | HSN/SAC Amount
                1 ICT charges for PRE-WIRED CONTROL PANEL FOR 6 ZONE, | 998351 | 8,98,305.08
                VACUUM ANNEALING FURNACE
                OUTPUT IGST | 1,61,694.91
                Rounded Off | 0.01
                Total | 8,98,305.08 | 1,61,694.91 | 1,61,694.91
                Company's Bank Details
                Bank Name | : HDFC Bank
                A/c No. | : 00102560001143
                """;
    }

    private String avTradingAuditNoiseOcr() {
        return """
                GSTIN : 27BLPPS1385F1ZM | Triplicate for Assesses.
                AV TRADING COMPANY
                PAN : BLPPS1385F
                Tel. : 9075151448/7719090608 email tavtradingcomp@gmail.com
                Party Details : | Invoice No. | AV/23-24/265
                DEPARTMENT OF ATOMIC ENERGY | Dated | : 29-01-2024
                DIRECTORATE OF PURCHASE AND STORES | Place of Supply : Telangana (36)
                HRPSU, NFC, P.O. ECIL, Hyderabad, TELANGANA-500062
                GSTIN/UIN = : 36AAAGN1030Q1Z9
                Order No. | : GEMC-511687773293120 | Date : 16-01-2024
                DESCRIPTION OF GOODS | HSN | QTY | UNIT | PRICE | AMOUNT
                CUTTING WHEEL 125MMX1.2MMX22MM | 6804 | 180.00 | Nos | 38.14 | 8,100.00
                MAKE - APIDOR
                Grand Total | 180.00 Nos | 8,100.00
                18% | 6,864.41 | 1,235.59 | 1,235.59
                BANK NAME : CANARA BANK
                A/C NO : 5096201000040
                IFSC CODE : CNRB0005096
                """;
    }

    private String irelAuditNoiseOcr() {
        return """
                Tax Invoice
                IREL (India) Limited
                (A Government of India Undertaking)
                IRN : 25391820e30b4bbabe32d1ef0b8635594626c6e3957829000f769261bb68ff7a
                Details of Supplier
                IREL (India) Limited,
                MANAVALAKURICHI, KANYAKUMARI DIST, KANYAKUMARI, Tamil Nadu, 629252
                Email : marketing-mk@irel.co.in
                Details of Buyer
                Name | NUCLEAR FUEL COMPLEX
                Address | NUCLEAR FUEL COMPLEX, ECIL(PO), HYDERABAD - 500062
                State Name and Code | Telangana [36]
                GSTIN | 36 AAAGN1030Q 129
                PAN | AAAGN1030Q
                GSTIN | 33AAACI2799F1ZL
                PAN | AAACI2799F
                SI No | Description Goods/Service | HSN Code | Unit | Quantity | Rate ( as per Unit) | Value | Dis./Rbt if any
                1 | ZIRCON 'MK' GRADE | 26151000 | Metric Ton | 15.000 | 181,750.00 | 2,726,250,00 | 0.00
                Taxable Value | CGST Rate | CGST Amount | SGST Rate | SGST Amount | IGST Rate | IGST Amount | Total (INR)
                2,726,250.00 | 5,00 | 136,312.50 | 2,862,562.50
                Total | 2,862,562.50
                Eway Bill No : 531554382815
                """;
    }

    private String poOverlapAddressOcr() {
        return """
                Tax Invoice
                ACME SUPPLIES LLP
                GSTIN : 29ABCDE1234F1Z5
                Invoice No : INV-9981
                Date : 12-Apr-2026
                Buyer (Bill to)
                Department of Atomic Energy Stores
                HRPSU, NFC, P.O. ECIL, Hyderabad, Telangana - 500062
                P.O. No. GEMC-12345
                Vehicle No : TS09AB1234
                GSTIN/UIN : 36AAAGN1030Q1Z9
                Description Qty Rate Amount
                Control Valve 1 10000 10000
                IGST 18% 1800
                Grand Total 11800
                """;
    }

    private String sparseFinancialSummaryOcr() {
        return """
                Tax Invoice
                ALPHA INDUSTRIES PRIVATE LIMITED
                GSTIN : 29ABCDE1234F1Z5
                Invoice No : INV9001
                Date : 09-Apr-2026
                Bill To
                OMEGA PROJECTS LLP
                GSTIN : 27PQRSX6789L1Z2
                Description Qty Rate Amount
                Control Panel 1 10000 10000
                Summary 10000 1800
                Amount Payable 11800
                """;
    }

    private String shortNumericColumnSplitOcr() {
        return """
                ACME INDUSTRIES PRIVATE LIMITED        Invoice No : 7
                GSTIN : 29ABCDE1234F1Z5                Date : 01.04.2026
                Buyer (Bill to)                        Vehicle No : TS09AB1234
                OMEGA PROJECTS LLP                     P.O. No. GEMC-12345
                GSTIN : 27PQRSX6789L1Z2
                Description Qty Rate Amount
                Service Charge 1 5000 5000
                IGST 18% 900
                Grand Total 5900
                """;
    }

    private String multiPageFirstPage() {
        return """
                Tax Invoice
                ACME PROCESS SYSTEMS PRIVATE LIMITED
                GSTIN : 29ABCDE1234F1Z5
                Invoice No : INV/24-25/0099
                Date : 05-Apr-2026
                Buyer (Bill to)
                OMEGA PROJECTS LLP
                GSTIN : 27PQRSX6789L1Z2
                Terms of Payment : 30 DAYS
                """;
    }

    private String multiPageMiddlePage() {
        return """
                Description of Goods HSN/SAC Qty Rate Amount
                Premium Control Panel 85371000 2 12500 25000
                Industrial Sensor Assembly 90318000 3 5000 15000
                """;
    }

    private String multiPageLastPage() {
        return """
                Bank Details : HDFC BANK | A/C NO 9988776655 | IFSC HDFC0000123
                Taxable Value 40000
                CGST 9% 3600
                SGST 9% 3600
                Grand Total 47200
                Amount Payable 47200
                """;
    }

    private String auditMysoreActualOcr() {
        return """
                Tax Invoice
                ql | Mysore Ammonia and Chemicals Limited
                Plot No.10/L1, Phase - III (Expansion), I.P Pashamylaram, Patancheru Mandal, Sangareddy
                District, Telangana
                Pin Code : 502307
                Phone | : 9000257937 | Email | : hyderabadoffice@mysoreammonia.com MSME | : UDYAM-TS-25-0000360
                GSTIN | : 36AABCC9037H1ZN | CIN | : U24121MH1998PLC115586 | PAN | : AABCCS037H
                Invoice No. | : GST2324/2808 | Transportation Mode : BY ROAD
                Invoice Date. | ; 6-Feb-24 | Vehicle No. | : AP-28-TD-2823
                Payment Terms | > IMMEDIATE | Challan No & Date : GST2324/2808 / 6-Feb-24
                Transporter Name : | Place of Supply | : Telangana
                E-Way Bill No/Date : | Customer PONo. : GEMC-511687726196900
                IEC No. | Customer PO Date. : 28-Sep-23
                Destination | > ECIL
                Details of Recipient ( Billed to) | : | Details of Consignee ( Shipped to)
                M/s.The Stores Officer/Asst. Stores Officer | M/s.The Stores Officer/Asst. Stores Officer
                Directorate of Purchase & Stores,, Hyderabad Regional Stores Unit,, | Directorate of Purchase & Stores,, Hyderabad Regional Stores Unit,,
                Nuclear Fuel Complex, ECIL (PO),, Hyderabad, | Nuclear Fuel Complex, ECIL (PO),, Hyderabad,
                Pin Code : 500062 | Pin Code : 500062
                State : Telangana | State Code : 36 | State : Telangana | State Code : 36
                GSTIN No. : 36AAAGN1030Q129 | PAN No. : AAAGN1030Q | GSTIN No. : 36AAAGN1030Q12Z9 | PAN No. : AAAGN1030Q
                Sr. | Description of | HSN/SAC | No.of | Qty | Rate | UOM | Taxable | GST | GST
                No| Goods/Services | Code | Pkgs | Value | % | Amount
                1 Anhydrous (Liquid) Ammonia / AmmoniaGas | 28141000 | 5X500 KG | 2500.000 | 76.00} kgs | 190000.00| 18.00 | 34200.00
                TOTAL | 34200.00
                CGST 9% - Sales | 17100.00
                SGST 9% - Sales | 17100.00
                Total Invoice Value (In Figure) : 224200.00
                """;
    }

    private String auditParthActualOcr() {
        return """
                PARTH ENERGY SYSTEMS.PVT. LTD
                G-1, Radhika Complex, Loha Mandi, S.C. Road, Jaipur -302001 Rajasthan
                Phone No. : 0141-2362811 Email : parthenergysystem@rediffmail.com
                GST NO..08AAECP5414C1ZR, State - Rajasthan, State Code - 08, PAN- AAECPS5414C
                TAX INVOICE
                Purchase Order No. Date : GEMC-511687764010683
                Invoice No. : : 107 | Purchase Order Dated : 13-01-2024
                Date | : 30-01-2024
                State : Rajasthan, State Code : 08 | Place of Supply : Hyderabad
                Billed to
                Sr. Manager Materials, Departmnet of Atomic Energy,
                HRPSU, NFC, P.O. ECIL,,
                HYDERABAD, TELANGANA-500062, India
                GST IN/ | 36AAAGN1030Q12Z9
                S. No | Name of Good | rial | Qt | UOM | Rate | Amount | Discount | Taxable
                BOSCH make cordless Drilling
                machine, Model Number GSR185.
                1 | ; | ; | 8467 | 9976.27 | 39905.08 | 39905.08
                Li professional
                Sub Total | 39905.08
                Add : IGST | 7182.92
                Total Amount After Tax | 47088.00
                """;
    }

    private String auditAvTradingActualOcr() {
        return """
                GSTIN : 27BLPPS1385F1ZM | Triplicate for Assesses.
                AV TRADING COMPANY
                Tel. : 9075151448/7719090608 email tavtradingcomp@gmail.com
                Party Details : | Invoice No. | AV/23-24/265
                DEPARTMENT OF ATOMIC ENERGY | Dated | : 29-01-2024
                DIRECTORATE OF PURCHASE AND STORES | Place of Supply : Telangana (36)
                HRPSU, NFC, P.O. ECIL,, Hyderabad, TELANGANA-500062
                GSTIN/UIN = : 36AAAGN1030Q129
                Order No. | : GEMC-511687773293120 | Date : 16-01-2024
                DESCRIPTION OF GOODS | HSN | QTY | UNIT | PRICE | AMOUNT(* )
                1 |CUTTING WHEEL 125MMX1.2MMX22MM | 6804 | 180.00|Nos | 38,14 | 8,100.00
                MAKE - APIDOR
                Grand Total | 180.00 Nos | 8,100.00
                18% | 6,864.41 1,235.59 1,235.59
                """;
    }

    private String auditMadhavActualOcr() {
        return """
                GSTIN : O6AAMCM3562G12D | GST INVOICE | Original For Buyer
                MADHAV PE FOAM PRIVATE LTD
                PAN.No : AAMCN3562G
                Invoice No MPE/23-24/01191 | Date : | 20/01/2024
                Details of Receiver (Billed to)
                NUCLEAR FUEL COMPLEX
                Hyderabad - 500062
                STATE ; TELANGANA | CODE : 36
                GSTIN : 36AAAGN10309129 PAN : AAAGN1030Q
                SN DESCRIPITION OF GOODS | H.S.N PKG | QTY UNT | RATE | SALE AMT | IG8T% DIS%
                1] TARANG MATTRESS?79*36 | x 76.0x | 94042190 | 1 | 70.00 Pcs | 4067.800 | 264746,.00 | 18.00
                TOTAL | BO | 1 | 70,00 | 284746.00
                PCS : | 70 TOTAL : | 70.00 | TAXABLE AMT | 284746.00
                G,S.T | 51254.28
                INVOICE AMT | 336000.00
                H.S.N. | GST % PKG | QTY | AMOUNT | I.GST
                94042190 18.00 | 1 | 70,00 284746.00 51254,28
                """;
    }

    private String auditAscencionActualOcr() {
        return """
                TAX INVOICE
                ASCENCION ELECTRONICS
                GSTIN : O7CXGPS0971P1ZP
                Invoice No. | > 295
                Dated | > 19-12-2023
                Department of Atomic Energy Stores
                HRPSU, NFC, P.O. ECIL,, HYDERABAD, TELANGANA-500062
                GSTIN / UIN | 36AAAGN1030Q1Z9
                S.N. |Description of Goods | HSN/SAC | Qty. | Unit | Price| IGST | IGST | Amount(% )
                Weller 200 Deg C ta 450 Deg C 24 V Out | 8515 | 1.00! Pcs. | 13,559.31 | 18.00 %| 2,440.68 | 15,999.99
                Desoldering Station Vath Digital Display
                MODEL. WELOLO
                Grand Total | 1.00 Pcs. | 15,999.99
                Tax Rate TaxableAmt. IGSTAmt. Total Tax
                18% | 13,559.31 2,440.68 | 2,440.68
                """;
    }
}
