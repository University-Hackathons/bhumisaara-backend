package com.bandits.bhumisaara.repository;

import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} backs the admin user list, whose four
 * filters (role, area, banned, search) are each independently optional.
 */
public interface UserRepository extends JpaRepository<UserEntity, Long>, JpaSpecificationExecutor<UserEntity> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.userId = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") Long id);
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    /** Callers must pass the lowercased address — the column is normalised on write. */
    Optional<UserEntity> findByWalletAddress(String walletAddress);

    boolean existsByEmail(String email);

    List<UserEntity> findByRole_RoleNameOrderByUsernameAsc(Role roleName);

    List<UserEntity> findByRole_RoleNameAndIsAssignedOrderByUsernameAsc(Role roleName, Boolean isAssigned);

    // ─── Platform administration ─────────────────────────────────────────────

    /**
     * The officers serving one area. A list rather than an Optional because
     * the one-officer-per-area rule is enforced in the service, not by a
     * database constraint — historic data may still hold two. Lowest user id
     * first, matching how {@code BatchTransferService} resolves the incumbent.
     */
    List<UserEntity> findByRole_RoleNameAndArea_AreaIdOrderByUserIdAsc(Role roleName, Long areaId);

    long countByRole_RoleName(Role roleName);

    long countByRole_RoleNameAndArea_AreaId(Role roleName, Long areaId);

    long countByIsBannedTrue();

    long countByWalletAddressIsNull();

    long countByRole_RoleNameAndWalletAddressIsNull(Role roleName);

    List<UserEntity> findAllByOrderByUsernameAsc();
}
