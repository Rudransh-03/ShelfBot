package com.localfilebrain.extract;

/**
 * Run-time options for an extraction (kept intentionally minimal for this
 * milestone). Currently only the currency descriptor applied to CURRENCY
 * fields.
 *
 * @param currency how CURRENCY fields are rendered; never null (defaults to the
 *                 neutral {@link CurrencyDescriptor#NONE} — Extract Mode forces
 *                 an explicit user choice before this reaches the engine)
 */
public record ExtractionOptions(CurrencyDescriptor currency) {

    public ExtractionOptions {
        if (currency == null) currency = CurrencyDescriptor.NONE;
    }

    public static ExtractionOptions defaults() {
        return new ExtractionOptions(CurrencyDescriptor.NONE);
    }
}
