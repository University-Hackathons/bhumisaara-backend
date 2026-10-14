package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.AssignUserAreaRequestDTO;
import com.bandits.bhumisaara.dto.request.ChangeRoleRequestDTO;
import com.bandits.bhumisaara.dto.request.ResetPasswordRequestDTO;
import com.bandits.bhumisaara.dto.response.AdminUserDetailDTO;
import com.bandits.bhumisaara.dto.response.AdminUserSummaryDTO;
import com.bandits.bhumisaara.dto.response.PageResponseDTO;
import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.RoleEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.ListingStatus;
import com.bandits.bhumisaara.enums.OrderStatus;
import com.bandits.bhumisaara.enums.RequestStatus;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.AreaRepository;
import com.bandits.bhumisaara.repository.DistributionLogRepository;
import com.bandits.bhumisaara.repository.FertilizerRequestRepository;
import com.bandits.bhumisaara.repository.MarketOrderRepository;
import com.bandits.bhumisaara.repository.ProductListingRepository;
import com.bandits.bhumisaara.repository.RoleRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Account administration for the platform operator.
 * <p>
 * <strong>Separation of duties.</strong> Everything here is user
 * administration. A {@code SYSTEM_ADMIN} can decide <em>who</em> may act in
 * the fertilizer system, and never acts in it themselves — no method on this
 * class mints, transfers, burns, issues credits, approves a claim, or touches
 * a batch, listing or order. That boundary is the reason the role exists.
 * <p>
 * Every mutating method resolves the actor through {@link CurrentUserProvider}
 * and writes an audit row inside its own transaction.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    /** Orders that still owe someone something, so still block a role change. */
    private static final Set<OrderStatus> OPEN_ORDER_STATUSES =
            EnumSet.of(OrderStatus.PENDING_CONFIRMATION, OrderStatus.CONFIRMED);

    /** Requests still moving through review or collection. */
    private static final Set<RequestStatus> OPEN_REQUEST_STATUSES =
            EnumSet.of(RequestStatus.PENDING, RequestStatus.APPROVED, RequestStatus.PARTIALLY_COLLECTED);

    private static final Set<Role> SELLER_ROLES =
            EnumSet.of(Role.PRIVATE_AGRO_DEALER, Role.ORGANIC_FERTILIZER_PRODUCER);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AreaRepository areaRepository;
    private final FertilizerRequestRepository fertilizerRequestRepository;
    private final DistributionLogRepository distributionLogRepository;
    private final MarketOrderRepository marketOrderRepository;
    private final ProductListingRepository productListingRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final AuditService auditService;

    // ─── Reads ───────────────────────────────────────────────────────────────

    /**
     * The user directory. Every filter is independently optional; passing none
     * returns everyone, newest account first.
     *
     * @param search matched case-insensitively against username and email
     */
    @Transactional(readOnly = true)
    public PageResponseDTO<AdminUserSummaryDTO> getUsers(Role role,
                                                         Long areaId,
                                                         Boolean isBanned,
                                                         String search,
                                                         int page,
                                                         int size) {
        List<Specification<UserEntity>> filters = new ArrayList<>();

        if (role != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("role").get("roleName"), role));
        }
        if (areaId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("area").get("areaId"), areaId));
        }
        if (isBanned != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("isBanned"), isBanned));
        }
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            filters.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)));
        }

        // A tautology rather than null when no filter is set: passing null to
        // findAll leans on nullability that has changed across Spring Data
        // versions, and `1 = 1` is unambiguous in every one of them.
        Specification<UserEntity> spec = filters.stream()
                .reduce(Specification::and)
                .orElse((root, query, cb) -> cb.conjunction());

        Page<UserEntity> results = userRepository.findAll(
                spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "userId")));

        return PageResponseDTO.from(results, this::mapToSummary);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDTO getUser(Long userId) {
        return mapToDetail(requireUser(userId));
    }

    // ─── Ban / unban ─────────────────────────────────────────────────────────

    @Transactional
    public AdminUserDetailDTO banUser(Long userId) {
        UserEntity actor = currentUserProvider.require();
        UserEntity target = requireUser(userId);

        // An admin who bans themselves locks the platform's own operator out
        // of the platform, with nobody left holding the key.
        if (target.getUserId().equals(actor.getUserId())) {
            throw new IllegalStateException("You cannot ban your own account");
        }

        // Peer admins are off limits in both directions: letting one operator
        // lock another out turns an account-management tool into a way to
        // seize sole control of the platform.
        if (isSystemAdmin(target)) {
            throw new IllegalStateException(
                    "System administrators cannot ban one another. Remove the SYSTEM_ADMIN role first.");
        }

        if (Boolean.TRUE.equals(target.getIsBanned())) {
            throw new IllegalStateException(target.getUsername() + " is already banned");
        }

        target.setIsBanned(true);
        UserEntity saved = userRepository.save(target);

        auditService.record(actor, AuditService.Action.USER_BANNED, saved.getUserId(), null,
                "Banned " + saved.getUsername() + " (" + describeRole(saved) + ")");

        return mapToDetail(saved);
    }

    @Transactional
    public AdminUserDetailDTO unbanUser(Long userId) {
        UserEntity actor = currentUserProvider.require();
        UserEntity target = requireUser(userId);

        if (!Boolean.TRUE.equals(target.getIsBanned())) {
            throw new IllegalStateException(target.getUsername() + " is not banned");
        }

        target.setIsBanned(false);
        UserEntity saved = userRepository.save(target);

        auditService.record(actor, AuditService.Action.USER_UNBANNED, saved.getUserId(), null,
                "Unbanned " + saved.getUsername() + " (" + describeRole(saved) + ")");

        return mapToDetail(saved);
    }

    // ─── Password reset ──────────────────────────────────────────────────────

    /**
     * Sets a new password for a user who has lost access to their account.
     * <p>
     * The plaintext is hashed on the way in and never leaves this method: it
     * is not returned, not logged, and not written to the audit trail. The
     * audit row proves a reset happened without recording what it set.
     */
    @Transactional
    public AdminUserDetailDTO resetPassword(Long userId, ResetPasswordRequestDTO request) {
        UserEntity actor = currentUserProvider.require();
        UserEntity target = requireUser(userId);

        target.setPassword(passwordEncoder.encode(request.getNewPassword()));
        target.setSessionVersion(target.getSessionVersion() + 1);
        UserEntity saved = userRepository.save(target);

        auditService.record(actor, AuditService.Action.USER_PASSWORD_RESET, saved.getUserId(), null,
                "Reset the password for " + saved.getUsername());

        return mapToDetail(saved);
    }

    // ─── Role assignment ─────────────────────────────────────────────────────

    /**
     * Moves a user to a different role.
     * <p>
     * A role is not a label here — it decides which wallet can burn a token
     * and which queue a record lands in. So the change is refused whenever the
     * user still holds state their new role could not own; the message always
     * names the specific blocker, because "cannot change role" with no reason
     * leaves an admin with nothing to do about it.
     */
    @Transactional
    public AdminUserDetailDTO changeRole(Long userId, ChangeRoleRequestDTO request) {
        UserEntity actor = currentUserProvider.require();
        UserEntity target = requireUser(userId);
        Role newRole = request.getRole();
        Role currentRole = target.getRole() != null ? target.getRole().getRoleName() : null;

        // Self-demotion is how an operator accidentally locks themselves out.
        if (target.getUserId().equals(actor.getUserId())) {
            throw new IllegalStateException(
                    "You cannot change your own role. Ask another system administrator.");
        }

        if (newRole == currentRole) {
            throw new IllegalStateException(target.getUsername() + " already holds the " + newRole + " role");
        }

        // Losing the last operator means nobody can appoint another — the
        // platform would need a database edit to recover.
        if (currentRole == Role.SYSTEM_ADMIN && userRepository.countByRole_RoleName(Role.SYSTEM_ADMIN) <= 1) {
            throw new IllegalStateException(
                    "This is the last remaining system administrator. Appoint another one before "
                            + "changing this account's role.");
        }

        requireNoBlockingDomainState(target, currentRole);

        RoleEntity roleEntity = roleRepository.findByRoleName(newRole)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + newRole));

        target.setRole(roleEntity);
        UserEntity saved = userRepository.save(target);

        auditService.record(actor, AuditService.Action.USER_ROLE_CHANGED, saved.getUserId(), null,
                "Changed the role of " + saved.getUsername() + " from "
                        + (currentRole != null ? currentRole : "none") + " to " + newRole);

        return mapToDetail(saved);
    }

    /**
     * Refuses a role change while the user still owns records only their
     * current role can own. Each branch names what has to be cleared first.
     */
    private void requireNoBlockingDomainState(UserEntity target, Role currentRole) {
        if (currentRole == null) {
            return;
        }

        if (currentRole == Role.AGRARIAN_SERVICE_OFFICER && target.getArea() != null) {
            throw new IllegalStateException(
                    target.getUsername() + " is the serving officer for " + target.getArea().getAreaName()
                            + ", " + target.getArea().getDistrict()
                            + ". Unassign them from that area before changing their role, "
                            + "or the area is left with no officer and no record of why.");
        }

        if (SELLER_ROLES.contains(currentRole)) {
            long activeListings = productListingRepository.countBySellerIdAndStatus(
                    target.getUserId(), ListingStatus.ACTIVE);
            if (activeListings > 0) {
                throw new IllegalStateException(
                        target.getUsername() + " has " + activeListings
                                + " active listing(s) in the marketplace. Pause or remove them first — "
                                + "a listing whose seller is no longer a seller cannot be fulfilled.");
            }

            long openOrders = marketOrderRepository.countBySellerIdAndStatusIn(
                    target.getUserId(), OPEN_ORDER_STATUSES);
            if (openOrders > 0) {
                throw new IllegalStateException(
                        target.getUsername() + " has " + openOrders
                                + " open order(s) awaiting fulfilment. Those must be completed or "
                                + "cancelled before the account changes role.");
            }
        }

        if (currentRole == Role.FARMER) {
            long openRequests = fertilizerRequestRepository.countByFarmer_UserIdAndStatusIn(
                    target.getUserId(), OPEN_REQUEST_STATUSES);
            if (openRequests > 0) {
                throw new IllegalStateException(
                        target.getUsername() + " has " + openRequests
                                + " subsidy request(s) still in review or awaiting collection. "
                                + "Only a farmer can hold those, so they must be settled first.");
            }

            long openOrders = marketOrderRepository.countByFarmerIdAndStatusIn(
                    target.getUserId(), OPEN_ORDER_STATUSES);
            if (openOrders > 0) {
                throw new IllegalStateException(
                        target.getUsername() + " has " + openOrders
                                + " open marketplace order(s). Only a farmer can confirm an order, "
                                + "so they must be completed or cancelled first.");
            }
        }
    }

    // ─── Area assignment ─────────────────────────────────────────────────────

    /**
     * Assigns, moves or clears an officer's area.
     * <p>
     * One officer per area is the rule the whole distribution flow leans on —
     * {@code BatchTransferService} resolves the recipient of a stock transfer
     * from the area alone, so a second officer there would silently make
     * deliveries ambiguous. Enforced here rather than by a unique index
     * because {@code users.area_id} is also set for farmers.
     */
    @Transactional
    public AdminUserDetailDTO assignArea(Long userId, AssignUserAreaRequestDTO request) {
        UserEntity actor = currentUserProvider.require();
        UserEntity target = requireUser(userId);

        if (target.getRole() == null || target.getRole().getRoleName() != Role.AGRARIAN_SERVICE_OFFICER) {
            throw new IllegalArgumentException(
                    target.getUsername() + " does not hold the AGRARIAN_SERVICE_OFFICER role, "
                            + "so they cannot be assigned to serve an area.");
        }

        if (request.getAreaId() == null) {
            AreaEntity previous = target.getArea();
            if (previous == null) {
                throw new IllegalStateException(target.getUsername() + " is not assigned to any area");
            }

            target.setArea(null);
            target.setIsAssigned(false);
            UserEntity saved = userRepository.save(target);

            auditService.record(actor, AuditService.Action.USER_AREA_CLEARED, saved.getUserId(),
                    "area:" + previous.getAreaId(),
                    "Unassigned " + saved.getUsername() + " from " + describeArea(previous));

            return mapToDetail(saved);
        }

        AreaEntity area = areaRepository.findById(request.getAreaId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Area not found with id: " + request.getAreaId()));

        if (!Boolean.TRUE.equals(area.getIsActive())) {
            throw new IllegalStateException(
                    describeArea(area) + " is deactivated. Reactivate it before assigning an officer.");
        }

        if (target.getArea() != null && target.getArea().getAreaId().equals(area.getAreaId())) {
            throw new IllegalStateException(
                    target.getUsername() + " already serves " + describeArea(area));
        }

        userRepository.findByRole_RoleNameAndArea_AreaIdOrderByUserIdAsc(
                        Role.AGRARIAN_SERVICE_OFFICER, area.getAreaId()).stream()
                .filter(incumbent -> !incumbent.getUserId().equals(target.getUserId()))
                .findFirst()
                .ifPresent(incumbent -> {
                    throw new IllegalStateException(
                            describeArea(area) + " is already served by " + incumbent.getUsername()
                                    + " (" + incumbent.getEmail() + "). Unassign them first — "
                                    + "an area can only have one serving officer.");
                });

        AreaEntity previous = target.getArea();
        target.setArea(area);
        target.setIsAssigned(true);
        UserEntity saved = userRepository.save(target);

        auditService.record(actor, AuditService.Action.USER_AREA_ASSIGNED, saved.getUserId(),
                "area:" + area.getAreaId(),
                "Assigned " + saved.getUsername() + " to " + describeArea(area)
                        + (previous != null ? " (moved from " + describeArea(previous) + ")" : ""));

        return mapToDetail(saved);
    }

    // ─── Wallet ──────────────────────────────────────────────────────────────

    /**
     * Unlinks a user's wallet so they can connect a new one.
     * <p>
     * <strong>Clear-only, and there is deliberately no counterpart that sets
     * an address.</strong> Tokens are addressed to whatever wallet the user's
     * row names: an admin who could write that field could point a government
     * admin's mint, an officer's stock transfer or a farmer's credits at a
     * wallet of their own choosing, and every on-chain record would look
     * perfectly legitimate. Clearing is safe because it grants nothing — the
     * user must connect and sign with the new wallet themselves, through
     * {@code PATCH /users/me/wallet}, which proves they hold its key.
     */
    @Transactional
    public AdminUserDetailDTO clearWallet(Long userId) {
        UserEntity actor = currentUserProvider.require();
        UserEntity target = requireUser(userId);

        if (target.getWalletAddress() == null) {
            throw new IllegalStateException(target.getUsername() + " has no wallet linked");
        }

        String previous = target.getWalletAddress();
        target.setWalletAddress(null);
        target.setSessionVersion(target.getSessionVersion() + 1);
        UserEntity saved = userRepository.save(target);

        // The cleared address is recorded in full: it is a public blockchain
        // identifier, and an audit row that doesn't say which wallet was
        // unlinked cannot answer the question it exists to answer.
        auditService.record(actor, AuditService.Action.USER_WALLET_CLEARED, saved.getUserId(), null,
                "Cleared the wallet " + previous + " from " + saved.getUsername()
                        + ". They must reconnect and sign with a wallet themselves.");

        return mapToDetail(saved);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private UserEntity requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with id: " + userId));
    }

    private boolean isSystemAdmin(UserEntity user) {
        return user.getRole() != null && user.getRole().getRoleName() == Role.SYSTEM_ADMIN;
    }

    private String describeRole(UserEntity user) {
        return user.getRole() != null ? user.getRole().getRoleName().name() : "no role";
    }

    private String describeArea(AreaEntity area) {
        return area.getAreaName() + ", " + area.getDistrict();
    }

    /**
     * {@code 0x1234…abcd}. Mirrors the frontend's {@code truncateAddress} so
     * both ends read the same, and keeps full addresses out of list views.
     */
    static String truncateAddress(String address) {
        if (address == null || address.length() <= 12) {
            return address;
        }
        return address.substring(0, 6) + "…" + address.substring(address.length() - 4);
    }

    AdminUserSummaryDTO mapToSummary(UserEntity user) {
        AreaEntity area = user.getArea();

        return AdminUserSummaryDTO.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().getRoleName() : null)
                .areaName(area != null ? area.getAreaName() : null)
                .district(area != null ? area.getDistrict() : null)
                .walletAddressTruncated(truncateAddress(user.getWalletAddress()))
                .walletLinked(user.getWalletAddress() != null)
                .isBanned(user.getIsBanned())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private AdminUserDetailDTO mapToDetail(UserEntity user) {
        AreaEntity area = user.getArea();
        Long id = user.getUserId();

        // Counted across both sides of every relationship rather than by role:
        // an account that used to be a farmer still owns the requests it filed,
        // and hiding them is exactly how an admin bans the wrong person.
        long distributions = distributionLogRepository.countByFarmerId(id)
                + distributionLogRepository.countByOfficerId(id);
        long orders = marketOrderRepository.countByFarmerId(id)
                + marketOrderRepository.countBySellerId(id);

        return AdminUserDetailDTO.builder()
                .userId(id)
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().getRoleName() : null)
                .fullName(user.getFullName())
                .address(user.getAddress())
                .contactNumber(user.getContactNumber())
                .areaId(area != null ? area.getAreaId() : null)
                .areaName(area != null ? area.getAreaName() : null)
                .district(area != null ? area.getDistrict() : null)
                .isAssigned(user.getIsAssigned())
                .walletAddressTruncated(truncateAddress(user.getWalletAddress()))
                .walletLinked(user.getWalletAddress() != null)
                .isBanned(user.getIsBanned())
                .createdAt(user.getCreatedAt())
                .fertilizerRequestCount(fertilizerRequestRepository.countByFarmer_UserId(id))
                .requestsReviewedCount(fertilizerRequestRepository.countByReviewedByOfficer_UserId(id))
                .distributionCount(distributions)
                .orderCount(orders)
                .listingCount(productListingRepository.countBySellerId(id))
                .build();
    }
}
