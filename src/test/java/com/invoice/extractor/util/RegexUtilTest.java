package com.invoice.extractor.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexUtilTest {

    @Test
    void repairsAndValidatesGstinWithOcrNoise() {
        String noisy = "O8AAAGN1030Q1Z8";
        String repaired = RegexUtil.repairGstinCandidate(noisy);

        assertNotNull(repaired);
        assertEquals("08AAAGN1030Q1Z8", repaired);
        assertTrue(RegexUtil.hasGstinChecksum(repaired));
        assertTrue(RegexUtil.isValidGstin(repaired));
    }

    @Test
    void returnsNullForClearlyInvalidGstinNoise() {
        assertNull(RegexUtil.repairGstinCandidate("INVALIDGSTINCODE"));
    }

    @Test
    void repairsBuyerGstinWithWrongSeparatorCharacter() {
        String repaired = RegexUtil.repairGstinCandidate("36AAAGN1030Q129");

        assertNotNull(repaired);
        assertEquals("36AAAGN1030Q1Z9", repaired);
        assertTrue(RegexUtil.hasGstinChecksum(repaired));
    }
}
