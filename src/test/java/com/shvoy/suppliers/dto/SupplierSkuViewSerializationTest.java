package com.shvoy.suppliers.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shvoy.UnitPrice;
import com.shvoy.suppliers.domain.SkuStatus;

/**
 * Pins the exact wire shape of {@link SupplierSkuView} — the response the
 * frontend types against. A field rename, reorder, added field, or changed
 * type must fail here rather than silently reaching the frontend's build.
 * Uses the application's own (Boot-configured) ObjectMapper via {@code
 * @JsonTest} so this asserts the real serialisation: {@code UnitPrice} as a
 * string amount + currency, {@code LocalDate} as {@code yyyy-MM-dd}, {@code
 * Instant} as ISO-8601 UTC.
 */
@JsonTest
class SupplierSkuViewSerializationTest {

    private static final UUID SKU_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUPPLIER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PRICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TIER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void pricedSkuSerialisesToTheContractShape() throws Exception {
        SkuResponse sku = new SkuResponse(SKU_ID, SUPPLIER_ID, "SKU-1", "Blue widget", SkuStatus.ACTIVE, 24,
            Instant.parse("2026-08-10T09:15:32.123Z"), Instant.parse("2026-08-11T10:00:00Z"));
        CurrentPriceView currentPrice = new CurrentPriceView(PRICE_ID, SKU_ID,
            new UnitPrice(new BigDecimal("2.0000"), "USD"), LocalDate.of(2026, 8, 1), null, true,
            Instant.parse("2026-08-01T00:00:00Z"), null);
        DiscountTierResponse tier = new DiscountTierResponse(TIER_ID, 100,
            new UnitPrice(new BigDecimal("1.5000"), "USD"), Instant.parse("2026-08-01T00:00:00Z"));

        SupplierSkuView view = new SupplierSkuView(sku, currentPrice, List.of(tier));

        String expected = "{"
            + "\"sku\":{"
            + "\"id\":\"11111111-1111-1111-1111-111111111111\","
            + "\"supplierId\":\"22222222-2222-2222-2222-222222222222\","
            + "\"code\":\"SKU-1\","
            + "\"description\":\"Blue widget\","
            + "\"status\":\"ACTIVE\","
            + "\"cartonSize\":24,"
            + "\"createdAt\":\"2026-08-10T09:15:32.123Z\","
            + "\"updatedAt\":\"2026-08-11T10:00:00Z\""
            + "},"
            + "\"currentPrice\":{"
            + "\"id\":\"33333333-3333-3333-3333-333333333333\","
            + "\"skuId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"unitPrice\":{\"amount\":\"2.0000\",\"currency\":\"USD\"},"
            + "\"validFrom\":\"2026-08-01\","
            + "\"validTo\":null,"
            + "\"inDate\":true,"
            + "\"createdAt\":\"2026-08-01T00:00:00Z\","
            + "\"updatedAt\":null"
            + "},"
            + "\"tiers\":[{"
            + "\"id\":\"44444444-4444-4444-4444-444444444444\","
            + "\"quantityThreshold\":100,"
            + "\"unitPrice\":{\"amount\":\"1.5000\",\"currency\":\"USD\"},"
            + "\"createdAt\":\"2026-08-01T00:00:00Z\""
            + "}]"
            + "}";

        assertThat(objectMapper.writeValueAsString(view)).isEqualTo(expected);
    }

    @Test
    void neverPricedSkuSerialisesWithNullCurrentPriceAndEmptyTiers() throws Exception {
        SkuResponse sku = new SkuResponse(SKU_ID, SUPPLIER_ID, "SKU-1", "Blue widget", SkuStatus.ACTIVE, null,
            Instant.parse("2026-08-10T09:15:32.123Z"), null);

        SupplierSkuView view = new SupplierSkuView(sku, null, List.of());

        String expected = "{"
            + "\"sku\":{"
            + "\"id\":\"11111111-1111-1111-1111-111111111111\","
            + "\"supplierId\":\"22222222-2222-2222-2222-222222222222\","
            + "\"code\":\"SKU-1\","
            + "\"description\":\"Blue widget\","
            + "\"status\":\"ACTIVE\","
            + "\"cartonSize\":null,"
            + "\"createdAt\":\"2026-08-10T09:15:32.123Z\","
            + "\"updatedAt\":null"
            + "},"
            + "\"currentPrice\":null,"
            + "\"tiers\":[]"
            + "}";

        assertThat(objectMapper.writeValueAsString(view)).isEqualTo(expected);
    }
}
