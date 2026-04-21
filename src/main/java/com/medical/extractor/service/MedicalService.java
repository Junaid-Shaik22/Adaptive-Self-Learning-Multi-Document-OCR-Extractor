package com.medical.extractor.service;

import com.medical.extractor.model.MedicalLeaveData;
import org.springframework.web.multipart.MultipartFile;

public interface MedicalService {
    MedicalLeaveData processMedicalCertificate(MultipartFile file);
}
