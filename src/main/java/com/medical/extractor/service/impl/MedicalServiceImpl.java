package com.medical.extractor.service.impl;

import com.medical.extractor.extractor.MedicalExtractor;
import com.medical.extractor.model.MedicalLeaveData;
import com.medical.extractor.model.MedicalOcrDocument;
import com.medical.extractor.service.MedicalOcrService;
import com.medical.extractor.service.MedicalService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MedicalServiceImpl implements MedicalService {
    private final MedicalOcrService medicalOcrService;
    private final MedicalExtractor medicalExtractor;

    public MedicalServiceImpl(MedicalOcrService medicalOcrService) {
        this.medicalOcrService = medicalOcrService;
        this.medicalExtractor = new MedicalExtractor();
    }

    @Override
    public MedicalLeaveData processMedicalCertificate(MultipartFile file) {
        MedicalOcrDocument document = medicalOcrService.extractDocument(file);
        return medicalExtractor.extract(document);
    }
}
