package com.shvoy.reconciliation.domain;

import java.math.BigDecimal;
import java.time.Instant;
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

/**
 * One line of a {@link Reconciliation} — see Story 5.3. Heterogeneous by
 * design: a {@code MATCHED} line carries all three legs and the computed
 * variances; a structural finding ({@code UNMATCHED_*}/{@code DUPLICATE_SKU})
 * carries only whichever legs exist and no variance. Hence most columns are
 * nullable, and the four {@code of…} factory methods below build the valid
 * shapes rather than a single constructor with easy-to-misorder nulls.
 *
 * <strong>Variance sign is the direction.</strong> {@code
 * unitPriceVariancePct}/{@code quantityVariancePct} are stored <em>signed</em>
 * — positive is an increase (PI above the reference), negative a decrease —
 * so a single value carries both "how far off" (its magnitude) and "which
 * way" (its sign), with no separate direction field to drift out of sync.
 * Direction is load-bearing downstream: Roadmap v2 gates price <em>increases</em>
 * behind the 2-of-N approval (5.5) but not decreases. Both percentages are
 * rounded HALF_EVEN to 2dp <em>before</em> storage (see {@code
 * VarianceCalculator}), so the stored, displayed, and compared values are
 * identical — which is what keeps 5.4's tolerance comparison deterministic
 * and stops frontend/backend disagreement at the boundary.
 *
 * The variance is persisted on <strong>every</strong> matched line,
 * including ones well within any tolerance that will auto-confirm (5.4) —
 * per the Product Owner's explicit ask, for per-supplier drift trending.
 * It's cheap now and impossible to backfill later, so it's never computed-
 * and-discarded on the pass path.
 */
@Entity
@Table(name = "reconciliation_lines")
public class ReconciliationLine extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reconciliation_id", nullable = false)
    private UUID reconciliationId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 20)
    private ReconciliationFindingType findingType;

    @Column(name = "po_unit_price_amount", precision = 19, scale = 4)
    private BigDecimal poUnitPriceAmount;

    @Column(name = "po_quantity")
    private Integer poQuantity;

    @Column(name = "pi_unit_price_amount", precision = 19, scale = 4)
    private BigDecimal piUnitPriceAmount;

    @Column(name = "pi_quantity")
    private Integer piQuantity;

    @Column(name = "price_file_unit_price_amount", precision = 19, scale = 4)
    private BigDecimal priceFileUnitPriceAmount;

    @Column(name = "price_file_price_found")
    private Boolean priceFilePriceFound;

    @Column(name = "unit_price_variance_pct", precision = 12, scale = 2)
    private BigDecimal unitPriceVariancePct;

    @Column(name = "quantity_variance_pct", precision = 12, scale = 2)
    private BigDecimal quantityVariancePct;

    @Column(name = "quantity_variance_abs")
    private Integer quantityVarianceAbs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReconciliationLine() {
    }

    private ReconciliationLine(UUID reconciliationId, UUID skuId, ReconciliationFindingType findingType) {
        this.reconciliationId = reconciliationId;
        this.skuId = skuId;
        this.findingType = findingType;
        this.createdAt = Instant.now();
    }

    /**
     * A SKU present once on each side. {@code unitPriceVariancePct} is null
     * when it couldn't be computed (a cross-currency line — meaningless
     * without an FX rate — or a missing PO price); {@code priceFileFound}/
     * {@code priceFileUnitPrice} carry the independent price-file leg, which
     * is null when 3.8 couldn't resolve a price for the as-of date.
     */
    public static ReconciliationLine ofMatched(UUID reconciliationId, UUID skuId,
            BigDecimal poUnitPriceAmount, int poQuantity, BigDecimal piUnitPriceAmount, int piQuantity,
            BigDecimal priceFileUnitPriceAmount, boolean priceFileFound,
            BigDecimal unitPriceVariancePct, BigDecimal quantityVariancePct, Integer quantityVarianceAbs) {
        ReconciliationLine line = new ReconciliationLine(reconciliationId, skuId, ReconciliationFindingType.MATCHED);
        line.poUnitPriceAmount = poUnitPriceAmount;
        line.poQuantity = poQuantity;
        line.piUnitPriceAmount = piUnitPriceAmount;
        line.piQuantity = piQuantity;
        line.priceFileUnitPriceAmount = priceFileUnitPriceAmount;
        line.priceFilePriceFound = priceFileFound;
        line.unitPriceVariancePct = unitPriceVariancePct;
        line.quantityVariancePct = quantityVariancePct;
        line.quantityVarianceAbs = quantityVarianceAbs;
        return line;
    }

    /** The supplier's PI added a line for a SKU the PO doesn't have — only the PI leg exists. */
    public static ReconciliationLine ofUnmatchedPiLine(UUID reconciliationId, UUID skuId,
            BigDecimal piUnitPriceAmount, int piQuantity) {
        ReconciliationLine line = new ReconciliationLine(reconciliationId, skuId,
            ReconciliationFindingType.UNMATCHED_PI_LINE);
        line.piUnitPriceAmount = piUnitPriceAmount;
        line.piQuantity = piQuantity;
        return line;
    }

    /** The PO has a line the supplier's PI omitted — only the PO leg exists. */
    public static ReconciliationLine ofUnmatchedPoLine(UUID reconciliationId, UUID skuId,
            BigDecimal poUnitPriceAmount, int poQuantity) {
        ReconciliationLine line = new ReconciliationLine(reconciliationId, skuId,
            ReconciliationFindingType.UNMATCHED_PO_LINE);
        line.poUnitPriceAmount = poUnitPriceAmount;
        line.poQuantity = poQuantity;
        return line;
    }

    /** The same SKU appears more than once on one side — pairing can't be assumed, so it's flagged with no legs. */
    public static ReconciliationLine ofDuplicateSku(UUID reconciliationId, UUID skuId) {
        return new ReconciliationLine(reconciliationId, skuId, ReconciliationFindingType.DUPLICATE_SKU);
    }

    public UUID getId() {
        return id;
    }

    public UUID getReconciliationId() {
        return reconciliationId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public ReconciliationFindingType getFindingType() {
        return findingType;
    }

    public BigDecimal getPoUnitPriceAmount() {
        return poUnitPriceAmount;
    }

    public Integer getPoQuantity() {
        return poQuantity;
    }

    public BigDecimal getPiUnitPriceAmount() {
        return piUnitPriceAmount;
    }

    public Integer getPiQuantity() {
        return piQuantity;
    }

    public BigDecimal getPriceFileUnitPriceAmount() {
        return priceFileUnitPriceAmount;
    }

    public Boolean getPriceFilePriceFound() {
        return priceFilePriceFound;
    }

    public BigDecimal getUnitPriceVariancePct() {
        return unitPriceVariancePct;
    }

    public BigDecimal getQuantityVariancePct() {
        return quantityVariancePct;
    }

    public Integer getQuantityVarianceAbs() {
        return quantityVarianceAbs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
