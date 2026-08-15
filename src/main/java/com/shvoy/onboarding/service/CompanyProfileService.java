package com.shvoy.onboarding.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.NotFoundException;
import com.shvoy.TenantContext;
import com.shvoy.TenantGuard;
import com.shvoy.onboarding.domain.Company;
import com.shvoy.onboarding.dto.CompanyProfileResponse;
import com.shvoy.onboarding.dto.UpdateCompanyProfileRequest;
import com.shvoy.onboarding.repository.CompanyRepository;

/**
 * Company itself isn't a {@link com.shvoy.TenantScoped} entity — it IS the
 * tenant, keyed by its own id — so, unlike every other repository in this
 * module, Hibernate's tenant filter does nothing for a {@code companies}
 * lookup by id. Ownership here is enforced explicitly: the path
 * {@code companyId} is checked against {@link TenantContext}, and the
 * actual fetch is always by the authenticated id, never the path one.
 */
@Service
public class CompanyProfileService {

    private final CompanyRepository companyRepository;

    CompanyProfileService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @Transactional(readOnly = true)
    public CompanyProfileResponse getProfile(UUID companyId) {
        return toResponse(resolveOwnCompany(companyId));
    }

    @Transactional
    public CompanyProfileResponse updateProfile(UUID companyId, UpdateCompanyProfileRequest request) {
        Company company = resolveOwnCompany(companyId);
        company.updateProfile(request.registeredAddress(), request.defaultDeliveryAddress(), request.country(),
            request.contactEmail(), request.contactPhone(), request.registrationNumber());
        return toResponse(companyRepository.save(company));
    }

    private Company resolveOwnCompany(UUID pathCompanyId) {
        TenantGuard.assertOwnCompanyId(pathCompanyId);
        return companyRepository.findById(TenantContext.get())
            .orElseThrow(() -> new NotFoundException("Company not found"));
    }

    private CompanyProfileResponse toResponse(Company company) {
        return new CompanyProfileResponse(company.getId(), company.getName(), company.getRegisteredAddress(),
            company.getDefaultDeliveryAddress(), company.getCountry(), company.getContactEmail(),
            company.getContactPhone(), company.getRegistrationNumber(), company.getCreatedAt(), company.getUpdatedAt());
    }
}
