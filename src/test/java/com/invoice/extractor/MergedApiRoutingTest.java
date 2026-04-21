package com.invoice.extractor;

import com.docextract.model.ExtractionResult;
import com.docextract.service.DocumentProcessingService;
import com.invoice.extractor.model.InvoiceData;
import com.invoice.extractor.service.InvoiceService;
import com.medical.extractor.model.MedicalLeaveData;
import com.medical.extractor.service.MedicalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Objects;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AdaptiveInvoiceExtractorApplication.class)
@AutoConfigureMockMvc
class MergedApiRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceService invoiceService;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private MedicalService medicalService;

    @Test
    void invoiceHealthEndpointRemainsAvailable() throws Exception {
        mockMvc.perform(get("/api/invoice/test"))
                .andExpect(status().isOk())
                .andExpect(content().string("API Working"));
    }

    @Test
    void swaggerApiDocsEndpointLoads() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(Objects.requireNonNull(MediaType.APPLICATION_JSON)))
                .andExpect(content().string(Objects.requireNonNull(containsString("/api/invoice/extract"))))
                .andExpect(content().string(Objects.requireNonNull(containsString("/api/medical/extract"))))
                .andExpect(content().string(Objects.requireNonNull(containsString("Extract data from an invoice"))))
                .andExpect(content().string(Objects.requireNonNull(containsString("Invoice Extraction"))))
                .andExpect(content().string(Objects.requireNonNull(containsString("Unified OCR Extraction System"))))
                .andExpect(content().string(Objects.requireNonNull(containsString("Extracts structured data from invoices, medical leave certificates, and Indian identity documents"))))
                .andExpect(content().string(Objects.requireNonNull(containsString("Medical Leave Certificate OCR"))));
    }

    @Test
    void invoiceExtractionEndpointUsesInvoiceApiRoute() throws Exception {
        InvoiceData invoiceData = new InvoiceData();
        invoiceData.setInvoiceNumber("INV-001");
        invoiceData.setStatus("SUCCESS");
        given(invoiceService.processInvoice(any())).willReturn(invoiceData);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.png",
                MediaType.IMAGE_PNG_VALUE,
                "invoice".getBytes()
        );

        mockMvc.perform(multipart("/api/invoice/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-001"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.rawText").doesNotExist())
                .andExpect(jsonPath("$.dynamicFields").doesNotExist())
                .andExpect(jsonPath("$.knownFields").doesNotExist())
                .andExpect(jsonPath("$.pagesProcessed").doesNotExist())
                .andExpect(jsonPath("$.templateId").doesNotExist());
    }

    @Test
    void aadhaarExtractionEndpointUsesSeparateRoute() throws Exception {
        ExtractionResult result = ExtractionResult.builder()
                .documentType("AADHAAR")
                .name("John Doe")
                .pagesProcessed(1)
                .build();
        given(documentProcessingService.process(any(), anyBoolean())).willReturn(result);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "aadhaar.png",
                "image/png",
                "aadhaar".getBytes()
        );

        mockMvc.perform(multipart("/extract-document").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.document_type").value("AADHAAR"))
                .andExpect(jsonPath("$.data.pages_processed").value(1));
    }

    @Test
    void medicalExtractionEndpointUsesSeparateRoute() throws Exception {
        MedicalLeaveData data = new MedicalLeaveData();
        data.setOrganizationName("NFC Hospital");
        data.setApplicantName("Ramesh Kumar");
        given(medicalService.processMedicalCertificate(any())).willReturn(data);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "medical.png",
                MediaType.IMAGE_PNG_VALUE,
                "medical".getBytes()
        );

        mockMvc.perform(multipart("/api/medical/extract").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("NFC Hospital"))
                .andExpect(jsonPath("$.applicantName").value("Ramesh Kumar"));
    }

    @Test
    void invoiceValidationErrorsReturnBadRequestInsteadOfServerError() throws Exception {
        given(invoiceService.processInvoice(any())).willThrow(new IllegalArgumentException("Unsupported file type. Upload PDF, JPG, JPEG, PNG, TIFF, or BMP."));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "bad".getBytes()
        );

        mockMvc.perform(multipart("/api/invoice/extract").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Bad request"))
                .andExpect(jsonPath("$.message").value("Unsupported file type. Upload PDF, JPG, JPEG, PNG, TIFF, or BMP."));
    }

    @Test
    void medicalValidationErrorsReturnBadRequestInsteadOfServerError() throws Exception {
        given(medicalService.processMedicalCertificate(any())).willThrow(new IllegalArgumentException("Uploaded medical certificate file is empty"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "medical.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/api/medical/extract").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Bad request"))
                .andExpect(jsonPath("$.message").value("Uploaded medical certificate file is empty"));
    }

    @Test
    void medicalPdfSetupFailuresReturnHelpfulServerErrorMessage() throws Exception {
        given(medicalService.processMedicalCertificate(any())).willThrow(
                new IllegalStateException("Medical PDF OCR requires Poppler pdftoppm. Install Poppler and set 'invoice.pdf.poppler.command' to the full executable path.")
        );

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "medical.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "pdf".getBytes()
        );

        mockMvc.perform(multipart("/api/medical/extract").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Medical extraction failed"))
                .andExpect(jsonPath("$.message").value("Medical PDF OCR requires Poppler pdftoppm. Install Poppler and set 'invoice.pdf.poppler.command' to the full executable path."));
    }
}
