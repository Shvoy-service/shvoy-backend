package com.shvoy.suppliers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shvoy.UnitPrice;

class SkuPriceTest {

    private final UnitPrice price = new UnitPrice(new BigDecimal("1.4275"), "GBP");

    @Test
    void isInDateWhenTodayFallsWithinTheValidityWindow() {
        SkuPrice skuPrice = new SkuPrice(
            UUID.randomUUID(), price, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(skuPrice.isInDate(LocalDate.of(2026, 6, 15))).isTrue();
    }

    @Test
    void isNotInDateBeforeValidFrom() {
        SkuPrice skuPrice = new SkuPrice(UUID.randomUUID(), price, LocalDate.of(2026, 6, 1), null);

        assertThat(skuPrice.isInDate(LocalDate.of(2026, 5, 31))).isFalse();
    }

    @Test
    void isNotInDateAfterValidTo() {
        SkuPrice skuPrice = new SkuPrice(
            UUID.randomUUID(), price, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));

        assertThat(skuPrice.isInDate(LocalDate.of(2026, 7, 1))).isFalse();
    }

    @Test
    void anOpenEndedValidToNeverExpires() {
        SkuPrice skuPrice = new SkuPrice(UUID.randomUUID(), price, LocalDate.of(2026, 1, 1), null);

        assertThat(skuPrice.isInDate(LocalDate.of(2099, 1, 1))).isTrue();
    }

    @Test
    void boundaryDatesAreInclusive() {
        SkuPrice skuPrice = new SkuPrice(
            UUID.randomUUID(), price, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertThat(skuPrice.isInDate(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(skuPrice.isInDate(LocalDate.of(2026, 12, 31))).isTrue();
    }

    @Test
    void getUnitPriceReturnsTheOriginalAmountAndCurrency() {
        SkuPrice skuPrice = new SkuPrice(UUID.randomUUID(), price, LocalDate.of(2026, 1, 1), null);

        assertThat(skuPrice.getUnitPrice()).isEqualTo(price);
    }
}
