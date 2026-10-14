package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.dto.request.UpdateWalletAddressRequestDTO;
import com.bandits.bhumisaara.entity.UserEntity;
import com.bandits.bhumisaara.entity.WalletChallengeEntity;
import com.bandits.bhumisaara.repository.UserRepository;
import com.bandits.bhumisaara.repository.WalletChallengeRepository;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;

class UserAccountWalletTest {
    private static final String ADDRESS = "0x" + "1".repeat(40);
    private CurrentUserProvider current;
    private UserRepository users;
    private WalletChallengeRepository challenges;
    private WalletSignatureVerifier signatures;
    private UserAccountService service;
    private UserEntity user;

    @BeforeEach void setup() {
        current = mock(CurrentUserProvider.class);
        users = mock(UserRepository.class);
        challenges = mock(WalletChallengeRepository.class);
        signatures = mock(WalletSignatureVerifier.class);
        user = UserEntity.builder().userId(4L).username("alice").build();
        when(current.require()).thenReturn(user);
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new UserAccountService(current, users, challenges, signatures);
    }

    @Test void challengeIsBoundToUserWalletAndFiveMinuteExpiry() {
        Instant before = Instant.now();
        var response = service.createWalletChallenge(ADDRESS);
        ArgumentCaptor<WalletChallengeEntity> saved = ArgumentCaptor.forClass(WalletChallengeEntity.class);
        verify(challenges).save(saved.capture());
        WalletChallengeEntity challenge = saved.getValue();
        assertEquals(4L, challenge.getUserId());
        assertEquals(ADDRESS, challenge.getWalletAddress());
        assertTrue(challenge.getMessage().contains("User: 4"));
        assertTrue(challenge.getMessage().contains(challenge.getId()));
        assertEquals(challenge.getMessage(), response.get("message"));
        assertTrue(challenge.getExpiresAt().isAfter(before.plusSeconds(295)));
        assertTrue(challenge.getExpiresAt().isBefore(before.plusSeconds(305)));
    }

    @Test void validChallengeLinksWalletOnce() {
        WalletChallengeEntity challenge = challenge(Instant.now().plusSeconds(60));
        when(challenges.findLocked("id")).thenReturn(Optional.of(challenge));
        when(signatures.verify(eq(ADDRESS), eq("message"), eq("signature"))).thenReturn(true);
        service.updateMyWalletAddress(request());
        assertEquals(ADDRESS, user.getWalletAddress());
        assertTrue(challenge.isUsed());
        assertThrows(IllegalArgumentException.class, () -> service.updateMyWalletAddress(request()));
    }

    @Test void expiredAndForgedChallengesAreRejected() {
        WalletChallengeEntity expired = challenge(Instant.now().minusSeconds(1));
        when(challenges.findLocked("id")).thenReturn(Optional.of(expired));
        assertThrows(IllegalArgumentException.class, () -> service.updateMyWalletAddress(request()));
        WalletChallengeEntity fresh = challenge(Instant.now().plusSeconds(60));
        when(challenges.findLocked("id")).thenReturn(Optional.of(fresh));
        assertThrows(IllegalArgumentException.class, () -> service.updateMyWalletAddress(request()));
        assertNull(user.getWalletAddress());
    }

    private WalletChallengeEntity challenge(Instant expires) {
        WalletChallengeEntity c = new WalletChallengeEntity();
        c.setId("id"); c.setUserId(4L); c.setWalletAddress(ADDRESS);
        c.setMessage("message"); c.setExpiresAt(expires);
        return c;
    }
    private UpdateWalletAddressRequestDTO request() {
        return new UpdateWalletAddressRequestDTO(ADDRESS, "id", "signature");
    }
}
