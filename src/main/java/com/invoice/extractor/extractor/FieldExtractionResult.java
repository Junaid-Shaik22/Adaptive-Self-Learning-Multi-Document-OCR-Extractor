package com.invoice.extractor.extractor;

public class FieldExtractionResult<T> {
    private final T value;
    private final String method;
    private final Integer lineNumber;

    public FieldExtractionResult(T value, String method, Integer lineNumber) {
        this.value = value;
        this.method = method;
        this.lineNumber = lineNumber;
    }

    public T getValue() {
        return value;
    }

    public String getMethod() {
        return method;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }
}
