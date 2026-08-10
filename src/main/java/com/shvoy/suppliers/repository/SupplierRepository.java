package com.shvoy.suppliers.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.suppliers.domain.Supplier;

/**
 * Deliberately no custom derived query methods (findByX, etc.) — see
 * UserRepository/CompanyRepository for the same convention. Spring Data
 * validates a custom query method against Hibernate at repository-bean-
 * creation time (application startup), which needs a resolvable tenant even
 * just to prepare the query shape — before any request, and thus before any
 * real tenant, exists. Only the base JpaRepository methods (findAll,
 * findById, save, ...) avoid this; anything else belongs in the service
 * layer as findAll().stream()... — see SupplierService, same pattern as
 * TeamManagementService.
 */
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
}
