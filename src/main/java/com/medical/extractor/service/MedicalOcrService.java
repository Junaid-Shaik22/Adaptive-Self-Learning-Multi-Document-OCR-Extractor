package com.medical.extractor.service;

import com.medical.extractor.model.MedicalOcrDocument;
import org.springframework.web.multipart.MultipartFile;

public interface MedicalOcrService {
    MedicalOcrDocument extractDocument(MultipartFile file);
}
