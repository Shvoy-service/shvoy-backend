package com.shvoy.onboarding.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.shvoy.onboarding.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {
}
