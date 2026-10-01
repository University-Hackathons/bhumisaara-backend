package com.bandits.bhumisaara.config;

import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.RoleEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.enums.Role;
import com.bandits.bhumisaara.repository.AreaRepository;
import com.bandits.bhumisaara.repository.RoleRepository;
import com.bandits.bhumisaara.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * First-boot seed for the rows the application cannot create for itself.
 *
 * <p>Two things make an empty database unusable without this:
 * <ul>
 *   <li>{@code roles} is a lookup table with no write endpoint, so registration
 *       fails with "Role not found" until it is populated.</li>
 *   <li>{@code SYSTEM_ADMIN}, {@code GOVERNMENT_ADMIN} and
 *       {@code AGRARIAN_SERVICE_OFFICER} are excluded from self-registration in
 *       {@code AuthServiceImpl.SELF_REGISTERABLE_ROLES} — they can only be
 *       granted by an existing system admin, which is a bootstrap deadlock on a
 *       fresh install.</li>
 * </ul>
 *
 * <p>Every step is idempotent and checked individually, so this runs safely on
 * every boot and on a database that is already half-populated: existing rows are
 * left exactly as they are, and in particular an operator who has changed a
 * seeded password will not have it reset back.
 *
 * <p><strong>Development and staging only.</strong> The {@code @Profile}
 * exclusion of {@code production} is deliberate — well-known credentials must
 * never reach a real deployment. A production install seeds roles out of band.
 */
@Slf4j
@Component
@Profile("!production")
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    /**
     * Shared by every seeded account. Fine for a local demo, and the reason this
     * seeder refuses to run under the production profile.
     */
    private static final String DEFAULT_PASSWORD = "Password123!";

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AreaRepository areaRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        seedRoles();
        AreaEntity demoArea = seedAreas();
        seedBootstrapUsers(demoArea);
    }

    /** The six roles of {@link Role}, with the descriptions the admin UI shows. */
    private void seedRoles() {
        record RoleSeed(Role role, String description) {
        }

        List<RoleSeed> seeds = List.of(
                new RoleSeed(Role.SYSTEM_ADMIN,
                        "Platform operator: user accounts, role grants, areas and the audit log."),
                new RoleSeed(Role.GOVERNMENT_ADMIN,
                        "Ministry: mints import batches, distributes to centres, issues subsidy credits."),
                new RoleSeed(Role.AGRARIAN_SERVICE_OFFICER,
                        "Agrarian service centre: approves farmer requests and hands fertilizer over."),
                new RoleSeed(Role.FARMER,
                        "Applies for the subsidy, collects fertilizer, spends subsidy credits."),
                new RoleSeed(Role.PRIVATE_AGRO_DEALER,
                        "Sells agricultural inputs in the marketplace and redeems credits."),
                new RoleSeed(Role.ORGANIC_FERTILIZER_PRODUCER,
                        "Sells organic fertilizer in the marketplace and redeems credits."));

        for (RoleSeed seed : seeds) {
            if (roleRepository.findByRoleName(seed.role()).isEmpty()) {
                roleRepository.save(RoleEntity.builder()
                        .roleName(seed.role())
                        .description(seed.description())
                        .build());
                log.info("Seeded role {}", seed.role());
            }
        }
    }

    /**
     * One area, so an officer has somewhere to serve and a farmer has somewhere
     * to belong on a fresh install. Further areas are created through the
     * system admin's area management screen.
     *
     * @return the demo area, whether it was just created or already present
     */
    private AreaEntity seedAreas() {
        String areaName = "Kandy";
        String district = "Kandy";

        if (areaRepository.existsByAreaNameIgnoreCaseAndDistrictIgnoreCase(areaName, district)) {
            return areaRepository.findByIsActiveTrueOrderByDistrictAscAreaNameAsc().stream()
                    .filter(a -> a.getAreaName().equalsIgnoreCase(areaName)
                            && a.getDistrict().equalsIgnoreCase(district))
                    .findFirst()
                    .orElse(null);
        }

        AreaEntity area = areaRepository.save(AreaEntity.builder()
                .areaName(areaName)
                .district(district)
                .isActive(true)
                .build());
        log.info("Seeded area {} / {}", areaName, district);
        return area;
    }

    /**
     * The three accounts that cannot be created through {@code /auth/register}.
     * Self-registerable roles are deliberately not seeded — signing a farmer up
     * through the real form is part of what the demo shows.
     */
    private void seedBootstrapUsers(AreaEntity demoArea) {
        createUserIfAbsent("sysadmin", "sysadmin@bhumisaara.lk", Role.SYSTEM_ADMIN,
                "System Administrator", null);
        createUserIfAbsent("govadmin", "govadmin@bhumisaara.lk", Role.GOVERNMENT_ADMIN,
                "Ministry of Agriculture", null);
        createUserIfAbsent("officer", "officer@bhumisaara.lk", Role.AGRARIAN_SERVICE_OFFICER,
                "Kandy Agrarian Service Centre", demoArea);
    }

    private void createUserIfAbsent(String username, String email, Role roleName,
                                    String fullName, AreaEntity area) {
        if (userRepository.existsByEmail(email)) {
            return;
        }

        RoleEntity role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new IllegalStateException(
                        "Role " + roleName + " missing after seeding — cannot create " + email));

        userRepository.save(UserEntity.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                .fullName(fullName)
                .role(role)
                .area(area)
                // An officer is only useful once attached to an area; the system
                // admin's assignment screen treats that flag as "has a posting".
                .isAssigned(area != null)
                .isBanned(false)
                .createdAt(LocalDateTime.now())
                .build());

        log.info("Seeded {} account: {} / {}", roleName, email, DEFAULT_PASSWORD);
    }
}
