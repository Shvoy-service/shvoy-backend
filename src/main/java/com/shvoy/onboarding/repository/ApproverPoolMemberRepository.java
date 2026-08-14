package com.shvoy.onboarding.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.onboarding.domain.ApproverPoolMember;

/**
 * Deliberately no custom derived query methods — same convention as every
 * repository in this codebase; see UserRepository/SupplierRepository. Filtering
 * (by user, active-eligibility) happens in the service over findAll().
 */
public interface ApproverPoolMemberRepository extends JpaRepository<ApproverPoolMember, UUID> {
}
