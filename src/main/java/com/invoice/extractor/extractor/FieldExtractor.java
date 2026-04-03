package com.invoice.extractor.extractor;

public interface FieldExtractor<T> {
    T extract(String[] lines, int[] zones);
}
