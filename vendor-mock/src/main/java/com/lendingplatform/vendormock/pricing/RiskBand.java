package com.lendingplatform.vendormock.pricing;

/**
 * Risk bands the vendor prices against (assumption A2: case-sensitive, these three values only).
 * Deliberately duplicated in quote-app rather than shared (ADR-002).
 */
public enum RiskBand {
    LOW(65),
    MEDIUM(55),
    HIGH(40);

    private final int baseRateBps;

    RiskBand(int baseRateBps) {
        this.baseRateBps = baseRateBps;
    }

    public int baseRateBps() {
        return baseRateBps;
    }
}
