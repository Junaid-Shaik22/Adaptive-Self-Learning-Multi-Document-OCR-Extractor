package com.invoice.extractor.service;

import org.springframework.web.multipart.MultipartFile;

public interface OcrService {
    String extractText(MultipartFile file);
}
