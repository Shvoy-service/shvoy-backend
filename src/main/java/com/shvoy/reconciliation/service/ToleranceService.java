package com.shvoy.reconciliation.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.reconciliation.domain.ToleranceSetting;
import com.shvoy.reconciliation.dto.ToleranceSettingResponse;
import com.shvoy.reconciliation.dto.UpdateToleranceSettingRequest;
import com.shvoy.reconciliation.repository.ToleranceSettingRepository;

/**
 * The account's reconciliation tolerance — read, configure, and resolve the
 * effective value (Story 5.4). Per the Product Owner: one configurable
 * setting per company, default ~2%.
 *
 * <p>Roadmap v2 said "per company/supplier, e.g. ±3%"; the Product Owner's
 * later, more specific answer is per-account at 2%, so that's what's built
 * (and docs/CONTRACT.md corrects the roadmap wording so the two don't sit
 * contradicting each other).
 *
 * <p>{@link #resolveEffectiveTolerance} is the single lookup every evaluation
 * goes through, so a future move to per-supplier tolerance is an extension
 * (add a supplier argument here) rather than a redesign of the call sites.
 */
@Service
public class ToleranceService {

    /** The default applied when a company hasn't configured one — so reconciliation works out of the box. */
    static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("2.00");

    private final ToleranceSettingRepository toleranceSettingRepository;

    ToleranceService(ToleranceSettingRepository toleranceSettingRepository) {
        this.toleranceSettingRepository = toleranceSettingRepository;
    }

    /**
     * The effective tolerance % for the current account — the configured
     * value, or {@link #DEFAULT_TOLERANCE} when unset. The one place a
     * tolerance is resolved, so per-supplier resolution can later slot in
     * here without touching {@code ToleranceEvaluationService}.
     */
    @Transactional(readOnly = true)
    public BigDecimal resolveEffectiveTolerance() {
        return findOwn()
            .map(ToleranceSetting::getTolerancePercentage)
            .orElse(DEFAULT_TOLERANCE);
    }

    @Transactional(readOnly = true)
    public ToleranceSettingResponse get() {
        return findOwn()
            .map(ToleranceService::toResponse)
            .orElseGet(() -> new ToleranceSettingResponse(DEFAULT_TOLERANCE, true));
    }

    /** Upsert: at most one row per company (see the unique index), so this updates it in place or creates the first. */
    @Transactional
    public ToleranceSettingResponse update(UpdateToleranceSettingRequest request) {
        ToleranceSetting setting = findOwn()
            .map(existing -> {
                existing.updateTolerancePercentage(request.tolerancePercentage());
                return existing;
            })
            .orElseGet(() -> new ToleranceSetting(request.tolerancePercentage()));
        return toResponse(toleranceSettingRepository.save(setting));
    }

    /** Tenant-filtered, so at most one row — see the repository's Javadoc. */
    private Optional<ToleranceSetting> findOwn() {
        return toleranceSettingRepository.findAll().stream().findFirst();
    }

    private static ToleranceSettingResponse toResponse(ToleranceSetting setting) {
        return new ToleranceSettingResponse(setting.getTolerancePercentage(), false);
    }
}
