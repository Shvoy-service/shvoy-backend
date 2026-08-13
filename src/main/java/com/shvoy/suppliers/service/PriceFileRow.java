package com.shvoy.suppliers.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * One data row of the canonical price-file CSV template (Story 3.5):
 * {@code sku_code,description,unit_price,currency,valid_from,valid_to}.
 * Fields are kept as raw strings until {@link #validate()} confirms they
 * parse cleanly — see PriceFileUploadService, which collects every row's
 * issues before applying any of them, rather than failing on the first bad
 * row.
 */
record PriceFileRow(
        int rowNumber, String skuCode, String description, String unitPriceRaw, String currencyRaw,
        String validFromRaw, String validToRaw) {

    private static final java.util.regex.Pattern DECIMAL_UP_TO_4DP =
        java.util.regex.Pattern.compile("^\\d+(\\.\\d{1,4})?$");
    private static final java.util.regex.Pattern CURRENCY_CODE = java.util.regex.Pattern.compile("^[A-Za-z]{3}$");

    List<String> validate() {
        List<String> issues = new ArrayList<>();
        if (isBlank(skuCode)) {
            issues.add("sku_code is required");
        }
        if (isBlank(unitPriceRaw)) {
            issues.add("unit_price is required");
        } else if (!DECIMAL_UP_TO_4DP.matcher(unitPriceRaw.trim()).matches()) {
            issues.add("unit_price must be a positive decimal with at most 4 decimal places");
        }
        if (isBlank(currencyRaw) || !CURRENCY_CODE.matcher(currencyRaw.trim()).matches()) {
            issues.add("currency must be a 3-letter ISO 4217 code");
        }
        LocalDate validFrom = parseDateOrNull(validFromRaw, "valid_from", issues);
        if (isBlank(validFromRaw) && validFrom == null) {
            issues.add("valid_from is required");
        }
        LocalDate validTo = isBlank(validToRaw) ? null : parseDateOrNull(validToRaw, "valid_to", issues);
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            issues.add("valid_to must not be before valid_from");
        }
        return issues.stream().map(issue -> "Row " + rowNumber + ": " + issue).toList();
    }

    BigDecimal unitPriceAmount() {
        return new BigDecimal(unitPriceRaw.trim());
    }

    String currency() {
        return currencyRaw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    LocalDate validFrom() {
        return LocalDate.parse(validFromRaw.trim());
    }

    LocalDate validTo() {
        return isBlank(validToRaw) ? null : LocalDate.parse(validToRaw.trim());
    }

    private static LocalDate parseDateOrNull(String raw, String fieldName, List<String> issues) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            issues.add(fieldName + " must be a valid date (yyyy-MM-dd)");
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
