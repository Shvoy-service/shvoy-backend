package com.shvoy.suppliers.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.suppliers.domain.Supplier;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
}
