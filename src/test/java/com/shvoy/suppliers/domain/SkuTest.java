package com.shvoy.suppliers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class SkuTest {

    @Test
    void isCartonMultipleTrueForAnExactMultiple() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);
        sku.update("SKU-1", null, SkuStatus.ACTIVE, 10);

        assertThat(sku.isCartonMultiple(30)).isTrue();
    }

    @Test
    void isCartonMultipleFalseForANonMultiple() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);
        sku.update("SKU-1", null, SkuStatus.ACTIVE, 10);

        assertThat(sku.isCartonMultiple(25)).isFalse();
    }

    @Test
    void isCartonMultipleAlwaysTrueWithNoCartonSize() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);

        assertThat(sku.isCartonMultiple(25)).isTrue();
    }

    @Test
    void nearestCartonMultipleRoundsToTheCloserSide() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);
        sku.update("SKU-1", null, SkuStatus.ACTIVE, 10);

        // 12 is 2 away from 10 and 8 away from 20 - nearer to 10.
        assertThat(sku.nearestCartonMultiple(12)).isEqualTo(10);
        // 18 is 8 away from 10 and 2 away from 20 - nearer to 20.
        assertThat(sku.nearestCartonMultiple(18)).isEqualTo(20);
    }

    @Test
    void nearestCartonMultipleRoundsUpOnAnExactTie() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);
        sku.update("SKU-1", null, SkuStatus.ACTIVE, 10);

        // 15 is exactly 5 away from both 10 and 20.
        assertThat(sku.nearestCartonMultiple(15)).isEqualTo(20);
    }

    @Test
    void nearestCartonMultipleReturnsTheQuantityUnchangedWhenAlreadyAMultiple() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);
        sku.update("SKU-1", null, SkuStatus.ACTIVE, 10);

        assertThat(sku.nearestCartonMultiple(30)).isEqualTo(30);
    }

    @Test
    void nearestCartonMultipleReturnsTheQuantityUnchangedWithNoCartonSize() {
        Sku sku = new Sku(UUID.randomUUID(), "SKU-1", null);

        assertThat(sku.nearestCartonMultiple(25)).isEqualTo(25);
    }
}
