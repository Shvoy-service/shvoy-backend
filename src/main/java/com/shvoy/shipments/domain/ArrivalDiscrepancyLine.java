package com.shvoy.shipments.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.shvoy.TenantScoped;

/** One SKU's arrival delta (Story 7.6): expected (the GRN snapshot) vs arrived, and the direction. */
@Entity
@Table(name = "arrival_discrepancy_lines")
public class ArrivalDiscrepancyLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "arrival_discrepancy_id", nullable = false)
    private UUID arrivalDiscrepancyId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "expected_quantity", nullable = false)
    private int expectedQuantity;

    @Column(name = "arrived_quantity", nullable = false)
    private int arrivedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private ArrivalDiscrepancyDirection direction;

    protected ArrivalDiscrepancyLine() {
    }

    public ArrivalDiscrepancyLine(UUID arrivalDiscrepancyId, UUID skuId, int expectedQuantity, int arrivedQuantity) {
        this.arrivalDiscrepancyId = arrivalDiscrepancyId;
        this.skuId = skuId;
        this.expectedQuantity = expectedQuantity;
        this.arrivedQuantity = arrivedQuantity;
        this.direction = arrivedQuantity < expectedQuantity
            ? ArrivalDiscrepancyDirection.SHORT : ArrivalDiscrepancyDirection.OVER;
    }

    public UUID getId() {
        return id;
    }

    public UUID getArrivalDiscrepancyId() {
        return arrivalDiscrepancyId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public int getExpectedQuantity() {
        return expectedQuantity;
    }

    public int getArrivedQuantity() {
        return arrivedQuantity;
    }

    public ArrivalDiscrepancyDirection getDirection() {
        return direction;
    }
}
