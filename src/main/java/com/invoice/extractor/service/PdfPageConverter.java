package com.invoice.extractor.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface PdfPageConverter {
    ConvertedPdfDocument convert(MultipartFile pdfFile) throws IOException;
}
