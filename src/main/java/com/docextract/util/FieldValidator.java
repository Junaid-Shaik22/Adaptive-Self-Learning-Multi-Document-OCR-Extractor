package com.docextract.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FieldValidator – validates extracted field values.
 *
 * Each validate* method returns a list of error strings (empty = passed).
 */
@Slf4j
@Component
public class FieldValidator {

    // ─── Strict patterns ─────────────────────────────────────────────────────
    private static final Pattern AADHAAR_STRICT  = Pattern.compile("^[2-9]\\d{11}$");
    private static final Pattern PAN_STRICT      = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern DL_LONG_FORMAT  = Pattern.compile(
            "^[A-Z]{2}[-\\s]?[0-9]{2}[-\\s]?[0-9]{4}[-\\s]?[0-9]{7}$");
    private static final Pattern DL_SHORT_FORMAT = Pattern.compile("^[A-Z]{2}[0-9]{13,14}$");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    // ─────────────────────────────────────────────────────────────────────────

    public List<String> validateAadhaar(String num) {
        List<String> errors = new ArrayList<>();
        if (isBlank(num)) { errors.add("Aadhaar number not found"); return errors; }
        String digits = num.replaceAll("\\s", "");
        if (!AADHAAR_STRICT.matcher(digits).matches()) {
            errors.add("Invalid Aadhaar: must be 12 digits starting 2–9. Got: " + num);
        }
        return errors;
    }

    public List<String> validatePan(String num) {
        List<String> errors = new ArrayList<>();
        if (isBlank(num)) { errors.add("PAN number not found"); return errors; }
        if (!PAN_STRICT.matcher(num).matches()) {
            errors.add("Invalid PAN: must be AAAAA9999A. Got: " + num);
        }
        return errors;
    }

    public List<String> validateDl(String num) {
        List<String> errors = new ArrayList<>();
        if (isBlank(num)) { errors.add("DL number not found"); return errors; }
        boolean ok = DL_LONG_FORMAT.matcher(num).matches()
                  || DL_SHORT_FORMAT.matcher(num.replaceAll("\\s", "")).matches();
        if (!ok) errors.add("Invalid DL number format. Got: " + num);
        return errors;
    }

    public List<String> validateDob(String dob) {
        List<String> errors = new ArrayList<>();
        if (isBlank(dob)) { errors.add("Date of birth not found"); return errors; }
        if (!isValidDate(dob)) errors.add("Invalid DOB format: " + dob + ". Expected DD/MM/YYYY.");
        return errors;
    }

    public List<String> validateDate(String date, String fieldName) {
        List<String> errors = new ArrayList<>();
        if (isBlank(date)) return errors; // optional fields are not hard errors
        if (!isValidDate(date)) errors.add("Invalid " + fieldName + " date: " + date);
        return errors;
    }

    public List<String> validateName(String name, String label) {
        List<String> errors = new ArrayList<>();
        if (isBlank(name))         errors.add(label + " not found");
        else if (name.length() < 2) errors.add(label + " too short: " + name);
        return errors;
    }

    /** Merge multiple error lists into one. */
    @SafeVarargs
    public final List<String> combine(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> l : lists) {
            if (l != null) all.addAll(l);
        }
        return all;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isValidDate(String date) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { LocalDate.parse(date, fmt); return true; }
            catch (DateTimeParseException ignored) {}
        }
        return false;
    }
}
