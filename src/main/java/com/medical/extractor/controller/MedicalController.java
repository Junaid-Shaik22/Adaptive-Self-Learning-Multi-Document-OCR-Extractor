package com.medical.extractor.controller;

import com.medical.extractor.model.MedicalLeaveData;
import com.medical.extractor.service.MedicalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/medical")
@Tag(name = "Medical Leave Extraction", description = "Extract structured fields from medical leave certificates")
public class MedicalController {
    private final MedicalService medicalService;

    public MedicalController(MedicalService medicalService) {
        this.medicalService = medicalService;
    }

    @Operation(
            summary = "Extract data from a medical leave certificate",
            description = "Accepts JPG/JPEG/PNG or PDF (single/multi-page) and returns structured medical leave fields."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Medical certificate extracted successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MedicalLeaveData.class)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "500", description = "Processing failure")
    })
    @PostMapping(
            value = "/extract",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<MedicalLeaveData> extractMedical(
            @Parameter(
                    description = "Medical leave certificate image or PDF",
                    required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE)
            )
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(medicalService.processMedicalCertificate(file));
    }
}
