package com.shvoy.onboarding.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.onboarding.domain.Company;
import com.shvoy.onboarding.repository.CompanyRepository;

/**
 * A narrow cross-module surface (PO-issuance gate) exposing company-level
 * defaults other modules pre-fill from — currently the default delivery address
 * {@code purchaseorders} stamps onto a new PO. {@code @NamedInterface} so
 * {@code purchaseorders} never reaches into the onboarding module's {@code
 * Company} internals.
 */
@NamedInterface("company-defaults")
@Service
public class CompanyDefaultsService {

    private final CompanyRepository companyRepository;

    CompanyDefaultsService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> defaultDeliveryAddress(UUID companyId) {
        return companyRepository.findById(companyId).map(Company::getDefaultDeliveryAddress);
    }
}
