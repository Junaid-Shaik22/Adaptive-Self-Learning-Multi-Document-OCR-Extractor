package com.medical.extractor.service.impl;

import com.invoice.extractor.service.PdfPageConverter;
import com.medical.extractor.model.MedicalOcrDocument;
import com.medical.extractor.model.OcrResult;
import com.medical.extractor.service.pipeline.MedicalOcrPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MedicalOcrServiceImplTest {

    private PdfPageConverter pdfPageConverter;
    private MedicalOcrPipeline ocrPipeline;
    private MedicalOcrServiceImpl service;

    @BeforeEach
    void setUp() {
        pdfPageConverter = Mockito.mock(PdfPageConverter.class);
        ocrPipeline = Mockito.mock(MedicalOcrPipeline.class);
        service = new MedicalOcrServiceImpl(pdfPageConverter, ocrPipeline);
    }

    @Test
    void wrapsPdfErrorsWithMedicalSpecificMessage() throws java.io.IOException {
        when(pdfPageConverter.convert(any())).thenThrow(new IllegalStateException("PDF OCR requires Poppler pdftoppm. Install Poppler and set 'invoice.pdf.poppler.command' to the full executable path."));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "medical.pdf",
                "application/pdf",
                "pdf".getBytes()
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.extractDocument(file));
        assertEquals(
                "Medical PDF OCR requires Poppler pdftoppm. Install Poppler and set 'invoice.pdf.poppler.command' to the full executable path.",
                ex.getMessage()
        );
    }

    @Test
    void extractsImageSuccessfully() {
        when(ocrPipeline.processImage(any(BufferedImage.class))).thenReturn(OcrResult.success("medical text one"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "medical.png",
                "image/png",
                "image".getBytes()
        );

        MedicalOcrDocument document = service.extractDocument(file);

        assertEquals(1, document.getPageCount());
        assertEquals("medical text one", document.getPages().get(0).getText());
    }
}
