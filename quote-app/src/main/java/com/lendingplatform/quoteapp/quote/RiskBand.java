package com.lendingplatform.quoteapp.quote;

/**
 * Risk bands a quote can be requested for (assumption A2). Deliberately duplicated from vendor-mock rather than
 * shared: the vendor is an external system (ADR-002).
 */
public enum RiskBand {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    private final String label;

    RiskBand(String label) {
        this.label = label;
    }

    /** How the band is shown to staff. */
    public String label() {
        return label;
    }
}
