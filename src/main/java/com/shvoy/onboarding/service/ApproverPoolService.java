package com.shvoy.onboarding.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shvoy.ConflictException;
import com.shvoy.ErrorCode;
import com.shvoy.NotFoundException;
import com.shvoy.TenantGuard;
import com.shvoy.ValidationException;
import com.shvoy.onboarding.domain.ApproverPoolMember;
import com.shvoy.onboarding.domain.ApproverPoolSettings;
import com.shvoy.onboarding.domain.Role;
import com.shvoy.onboarding.domain.User;
import com.shvoy.onboarding.domain.UserStatus;
import com.shvoy.onboarding.dto.ApproverPoolMemberResponse;
import com.shvoy.onboarding.dto.ApproverPoolResponse;
import com.shvoy.onboarding.repository.ApproverPoolMemberRepository;
import com.shvoy.onboarding.repository.ApproverPoolSettingsRepository;
import com.shvoy.onboarding.repository.UserRepository;

/**
 * The company's approver pool — the named people eligible for the
 * price-increase sign-off gate, and the "N of pool" threshold (Story 5.6).
 * This underpins 5.5's 2-of-N gate: without a configured pool there's nobody
 * to route a price increase to.
 *
 * <p>Lives in {@code onboarding} because it's company/user governance —
 * ADMIN configures it alongside roles and team membership, and validating
 * that a member is an active {@link Role#APPROVER} user is an internal
 * {@code UserRepository} check rather than a cross-module hop.
 *
 * <p>{@link #resolveEligibleApprovers}/{@link #requiredSignOffCount} are this
 * class's {@code @NamedInterface} surface — the read model 5.5 (in {@code
 * reconciliation}) consumes to route and count sign-offs, without the pool
 * entities or {@code User} being exposed directly.
 *
 * <p><strong>The load-bearing invariant:</strong> the required count must
 * never exceed the number of <em>active</em> pool members, or the gate
 * becomes an unpassable wall. It's enforced whenever the count is set and
 * whenever a member is removed. Eligibility (active + still APPROVER) is
 * resolved against the live user every time — a deactivated member is
 * excluded from the eligible set without the pool ever being mutated, so
 * "active pool size" is always computed live, never stale.
 */
@NamedInterface("approver-pool")
@Service
public class ApproverPoolService {

    /** Matches the business rule's 2-of-3 example; applies when a company hasn't set its own count. */
    static final int DEFAULT_REQUIRED_SIGN_OFF_COUNT = 2;

    private final ApproverPoolMemberRepository approverPoolMemberRepository;
    private final ApproverPoolSettingsRepository approverPoolSettingsRepository;
    private final UserRepository userRepository;

    ApproverPoolService(ApproverPoolMemberRepository approverPoolMemberRepository,
            ApproverPoolSettingsRepository approverPoolSettingsRepository,
            UserRepository userRepository) {
        this.approverPoolMemberRepository = approverPoolMemberRepository;
        this.approverPoolSettingsRepository = approverPoolSettingsRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ApproverPoolResponse getPool(UUID companyId) {
        TenantGuard.assertOwnCompanyId(companyId);
        return buildResponse();
    }

    /**
     * Adds a named user to the pool. The user must exist in the caller's
     * company (cross-tenant/unknown → {@code NOT_FOUND}), be {@code ACTIVE},
     * and hold the {@code APPROVER} role ({@code INELIGIBLE_APPROVER}
     * otherwise). A user already in the pool is a plain validation error, not
     * a silent no-op.
     */
    @Transactional
    public ApproverPoolResponse addMember(UUID companyId, UUID userId) {
        TenantGuard.assertOwnCompanyId(companyId);
        User user = findOwnUser(userId);
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() != Role.APPROVER) {
            throw new ConflictException(ErrorCode.INELIGIBLE_APPROVER,
                "User must be an active APPROVER to join the approver pool");
        }
        if (isMember(userId)) {
            throw new ValidationException("User is already in the approver pool");
        }
        approverPoolMemberRepository.save(new ApproverPoolMember(userId));
        return buildResponse();
    }

    /**
     * Removes a member. Rejected ({@code APPROVER_COUNT_EXCEEDS_POOL}) if it
     * would drop the active pool below the required count — the gate must
     * stay passable. Removing an <em>already-ineligible</em> member (a
     * deactivated user still listed) never trips this, since they don't count
     * toward the active size.
     */
    @Transactional
    public ApproverPoolResponse removeMember(UUID companyId, UUID userId) {
        TenantGuard.assertOwnCompanyId(companyId);
        ApproverPoolMember member = approverPoolMemberRepository.findAll().stream()
            .filter(m -> m.getUserId().equals(userId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("User is not in the approver pool"));

        int requiredCount = resolveRequiredCount();
        long activeSizeAfterRemoval = eligibleApproverIds().stream()
            .filter(id -> !id.equals(userId))
            .count();
        if (activeSizeAfterRemoval < requiredCount) {
            throw new ConflictException(ErrorCode.APPROVER_COUNT_EXCEEDS_POOL,
                "Removing this member would leave fewer active approvers (" + activeSizeAfterRemoval
                    + ") than the required sign-off count (" + requiredCount + ")");
        }
        approverPoolMemberRepository.delete(member);
        return buildResponse();
    }

    /**
     * Sets the required sign-off count. Rejected ({@code
     * APPROVER_COUNT_EXCEEDS_POOL}) if it exceeds the current active pool
     * size — the single most important validation in the story, since a
     * count larger than the pool makes every price increase impossible to
     * approve. The minimum-of-1 is enforced by the request DTO.
     */
    @Transactional
    public ApproverPoolResponse setRequiredCount(UUID companyId, int requiredCount) {
        TenantGuard.assertOwnCompanyId(companyId);
        long activePoolSize = eligibleApproverIds().size();
        if (requiredCount > activePoolSize) {
            throw new ConflictException(ErrorCode.APPROVER_COUNT_EXCEEDS_POOL,
                "Required sign-off count (" + requiredCount + ") cannot exceed the active pool size ("
                    + activePoolSize + ")");
        }
        ApproverPoolSettings settings = findSettings()
            .map(existing -> {
                existing.updateRequiredSignOffCount(requiredCount);
                return existing;
            })
            .orElseGet(() -> new ApproverPoolSettings(requiredCount));
        approverPoolSettingsRepository.save(settings);
        return buildResponse();
    }

    // --- cross-module read surface (Story 5.5 consumes this) ---

    /**
     * The set of pool members who are <em>currently</em> eligible to sign off
     * — active users still holding the {@code APPROVER} role. This is where
     * "deactivated users are ineligible at use time" is enforced (5.6's AC):
     * the pool itself is never mutated on deactivation; the ineligible member
     * is simply filtered out here. Tenant-scoped via {@code TenantContext},
     * like every other cross-module surface.
     */
    @Transactional(readOnly = true)
    public Set<UUID> resolveEligibleApprovers() {
        return eligibleApproverIds();
    }

    /** The required sign-off count for the current tenant — the configured value or the default. */
    @Transactional(readOnly = true)
    public int requiredSignOffCount() {
        return resolveRequiredCount();
    }

    /**
     * Email addresses of the currently-eligible pool approvers — the
     * recipients 5.5 notifies that a routed PI awaits sign-off, through the
     * shared {@code EmailSender} seam. Returns addresses only (no ids), since
     * that's all a notification needs; same eligibility filter as {@link
     * #resolveEligibleApprovers}.
     */
    @Transactional(readOnly = true)
    public List<String> resolveEligibleApproverEmails() {
        Map<UUID, User> usersById = userRepository.findAll().stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        return approverPoolMemberRepository.findAll().stream()
            .map(member -> usersById.get(member.getUserId()))
            .filter(ApproverPoolService::isEligible)
            .map(User::getEmail)
            .toList();
    }

    // --- internals ---

    private Set<UUID> eligibleApproverIds() {
        Map<UUID, User> usersById = userRepository.findAll().stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        return approverPoolMemberRepository.findAll().stream()
            .map(ApproverPoolMember::getUserId)
            .filter(userId -> isEligible(usersById.get(userId)))
            .collect(Collectors.toSet());
    }

    private static boolean isEligible(User user) {
        return user != null && user.getStatus() == UserStatus.ACTIVE && user.getRole() == Role.APPROVER;
    }

    private boolean isMember(UUID userId) {
        return approverPoolMemberRepository.findAll().stream()
            .anyMatch(m -> m.getUserId().equals(userId));
    }

    private int resolveRequiredCount() {
        return findSettings()
            .map(ApproverPoolSettings::getRequiredSignOffCount)
            .orElse(DEFAULT_REQUIRED_SIGN_OFF_COUNT);
    }

    private Optional<ApproverPoolSettings> findSettings() {
        return approverPoolSettingsRepository.findAll().stream().findFirst();
    }

    private User findOwnUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
        TenantGuard.assertOwned(user);
        return user;
    }

    private ApproverPoolResponse buildResponse() {
        Map<UUID, User> usersById = userRepository.findAll().stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));

        List<ApproverPoolMemberResponse> members = approverPoolMemberRepository.findAll().stream()
            .sorted(Comparator.comparing(ApproverPoolMember::getCreatedAt))
            .map(member -> toMemberResponse(member, usersById.get(member.getUserId())))
            .toList();

        int eligibleMemberCount = (int) members.stream().filter(ApproverPoolMemberResponse::eligible).count();
        Optional<ApproverPoolSettings> settings = findSettings();
        int requiredCount = settings.map(ApproverPoolSettings::getRequiredSignOffCount)
            .orElse(DEFAULT_REQUIRED_SIGN_OFF_COUNT);

        return new ApproverPoolResponse(requiredCount, settings.isEmpty(), eligibleMemberCount, members);
    }

    private static ApproverPoolMemberResponse toMemberResponse(ApproverPoolMember member, User user) {
        return new ApproverPoolMemberResponse(
            member.getUserId(),
            user == null ? null : user.getEmail(),
            user == null ? null : user.getRole(),
            user == null ? null : user.getStatus(),
            isEligible(user));
    }
}
