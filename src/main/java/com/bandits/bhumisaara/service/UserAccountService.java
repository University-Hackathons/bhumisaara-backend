package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.UpdateProfileRequestDTO;
import com.bandits.bhumisaara.dto.request.UpdateWalletAddressRequestDTO;
import com.bandits.bhumisaara.dto.response.ProfileResponseDTO;
import com.bandits.bhumisaara.dto.response.WalletAddressResponseDTO;
import com.bandits.bhumisaara.entity.AreaEntity;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.entity.WalletChallengeEntity;
import com.bandits.bhumisaara.repository.WalletChallengeRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository userRepository;
    private final WalletChallengeRepository challengeRepository;
    private final WalletSignatureVerifier signatureVerifier;

    @Transactional(readOnly = true)
    public WalletAddressResponseDTO getMyWalletAddress() {
        return mapToDTO(currentUserProvider.require());
    }

    /** The signed-in user's own profile, whatever their role. */
    @Transactional(readOnly = true)
    public ProfileResponseDTO getMyProfile() {
        return mapToProfileDTO(currentUserProvider.require());
    }

    /**
     * Saves the profile details of the signed-in user. Always their own record —
     * there is no user id in the payload, so this cannot edit anyone else.
     * <p>
     * Username, email and role are identity and stay untouched; the wallet and
     * the area have their own endpoints and are not cleared by a profile save.
     */
    @Transactional
    public ProfileResponseDTO updateMyProfile(UpdateProfileRequestDTO request) {
        UserEntity user = currentUserProvider.require();

        user.setFullName(request.getFullName().trim());
        user.setAddress(request.getAddress().trim());
        user.setContactNumber(request.getContactNumber().trim());

        return mapToProfileDTO(userRepository.save(user));
    }

    /**
     * Links the connected wallet to the authenticated user. Called by the frontend
     * the first time an address is seen; re-sending the same address is a no-op, and
     * connecting a different wallet replaces the stored one.
     */
    @Transactional
    public Map<String, String> createWalletChallenge(String address) {
        if (address == null || !address.matches("(?i)0x[0-9a-f]{40}"))
            throw new IllegalArgumentException("Invalid wallet address");
        UserEntity user = currentUserProvider.require();
        WalletChallengeEntity challenge = new WalletChallengeEntity();
        challenge.setId(UUID.randomUUID().toString());
        challenge.setUserId(user.getUserId());
        challenge.setWalletAddress(address.toLowerCase());
        challenge.setExpiresAt(Instant.now().plusSeconds(300));
        challenge.setMessage("Bhumisaara wallet link\nUser: " + user.getUserId()
                + "\nWallet: " + challenge.getWalletAddress() + "\nNonce: " + challenge.getId()
                + "\nExpires: " + challenge.getExpiresAt());
        challengeRepository.save(challenge);
        return Map.of("challengeId", challenge.getId(), "message", challenge.getMessage());
    }

    @Transactional
    public WalletAddressResponseDTO updateMyWalletAddress(UpdateWalletAddressRequestDTO request) {
        UserEntity user = currentUserProvider.require();

        // Normalise here as well as in UserEntity's lifecycle callback, so the
        // uniqueness lookup below compares like with like.
        String walletAddress = request.getWalletAddress().trim().toLowerCase();

        WalletChallengeEntity challenge = challengeRepository.findLocked(request.getChallengeId())
                .orElseThrow(() -> new IllegalArgumentException("Wallet challenge not found"));
        if (challenge.isUsed() || challenge.getExpiresAt().isBefore(Instant.now())
                || !challenge.getUserId().equals(user.getUserId())
                || !challenge.getWalletAddress().equals(walletAddress))
            throw new IllegalArgumentException("Wallet challenge expired, used, or mismatched");
        if (!signatureVerifier.verify(walletAddress, challenge.getMessage(), request.getSignature()))
            throw new IllegalArgumentException("Invalid wallet signature");
        challenge.setUsed(true);
        challengeRepository.save(challenge);

        userRepository.findByWalletAddress(walletAddress).ifPresent(owner -> {
            if (!owner.getUserId().equals(user.getUserId())) {
                throw new IllegalStateException(
                        "That wallet address is already linked to another account");
            }
        });

        user.setWalletAddress(walletAddress);

        return mapToDTO(userRepository.save(user));
    }

    private ProfileResponseDTO mapToProfileDTO(UserEntity user) {
        AreaEntity area = user.getArea();

        return ProfileResponseDTO.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().getRoleName() : null)
                .fullName(user.getFullName())
                .address(user.getAddress())
                .contactNumber(user.getContactNumber())
                .walletAddress(user.getWalletAddress())
                .areaId(area != null ? area.getAreaId() : null)
                .areaName(area != null ? area.getAreaName() : null)
                .district(area != null ? area.getDistrict() : null)
                .createdAt(user.getCreatedAt())
                .build();
    }

    private WalletAddressResponseDTO mapToDTO(UserEntity user) {
        return WalletAddressResponseDTO.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .walletAddress(user.getWalletAddress())
                .build();
    }
}
