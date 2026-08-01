package com.shvoy.onboarding.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.onboarding.domain.Company;

public interface CompanyRepository extends JpaRepository<Company, UUID> {
}
