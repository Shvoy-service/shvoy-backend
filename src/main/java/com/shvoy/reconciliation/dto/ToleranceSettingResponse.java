package com.shvoy.reconciliation.dto;

import java.math.BigDecimal;

/**
 * The account's effective reconciliation tolerance (Story 5.4). {@code
 * usingDefault} is true when the company hasn't configured one and the
 * built-in default is in effect — so the UI can show "default" rather than
 * implying someone set it.
 */
public record ToleranceSettingResponse(
    BigDecimal tolerancePercentage,
    boolean usingDefault
) {
}
