package com.bandits.bhumisaara.service;

import com.bandits.bhumisaara.repository.RecordedChainTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChainTransactionVerifierTest {
    private static final String HASH = "0x" + "a".repeat(64);
    private static final String CONTRACT = "0x" + "1".repeat(40);
    private static final String ADMIN = "0x" + "2".repeat(40);
    private static final String FARMER = "0x" + "3".repeat(40);
    private static final String TOPIC = "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62";
    private MockRestServiceServer server;
    private RecordedChainTransactionRepository recorded;
    private ChainTransactionVerifier verifier;

    @BeforeEach void setup() {
        recorded = mock(RecordedChainTransactionRepository.class);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new ChainTransactionVerifier(builder, new ObjectMapper(), recorded,
                "http://unused.example", CONTRACT, 80002);
    }

    @Test void acceptsConfirmedMatchingMintAndReservesHash() {
        responses(receipt(CONTRACT, 7));
        verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7);
        verify(recorded).saveAndFlush(any());
        server.verify();
    }

    @Test void rejectsForgedHashWithoutReceipt() {
        responses("null");
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7));
        verify(recorded, never()).saveAndFlush(any());
    }

    @Test void rejectsRevertedReceipt() {
        responses(receipt(CONTRACT, 7).replace("\"status\":\"0x1\"", "\"status\":\"0x0\""));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7));
    }

    @Test void rejectsWrongContractAndAmount() {
        responses(receipt("0x" + "4".repeat(40), 7));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7));
    }

    @Test void rejectsWrongAmount() {
        responses(receipt(CONTRACT, 8));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7));
    }


    @Test void validatesStockAndCreditTransfersWithExactPartiesAndToken() {
        responses(movementReceipt(CONTRACT, ADMIN, FARMER, ADMIN, 5, 7));
        verifier.verifyTransfer(HASH, ADMIN, FARMER, "5", 7);
        server.verify();
    }

    @Test void rejectsWrongSenderRecipientAndToken() {
        responses(movementReceipt(CONTRACT, ADMIN, FARMER, ADMIN, 5, 7));
        assertThrows(IllegalArgumentException.class, () -> verifier.verifyTransfer(HASH, FARMER, ADMIN, "6", 7));
    }

    @Test void validatesHandoverAndRedemptionBurns() {
        responses(movementReceipt(CONTRACT, ADMIN, "0x" + "0".repeat(40), ADMIN, 5, 7));
        verifier.verifyBurn(HASH, ADMIN, "5", 7);
        server.verify();
    }

    @Test void rejectsReplayedHash() {
        when(recorded.existsById(HASH)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7));
        server.verify();
    }

    @Test void rejectsHashAlreadyPresentInOlderLedgerRow() {
        when(recorded.existsInLegacyLedgers(HASH)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> verifier.verifyMint(HASH, ADMIN, FARMER, "5", 7));
        server.verify();
    }

    private void responses(String receipt) {
        respond("eth_chainId", "\"0x13882\"");
        respond("eth_getTransactionByHash", "{\"hash\":\"" + HASH + "\",\"to\":\"" + CONTRACT + "\"}");
        respond("eth_getTransactionReceipt", receipt);
    }

    private void respond(String method, String result) {
        server.expect(once(), requestTo("http://unused.example"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"method\":\"" + method + "\"")))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}", MediaType.APPLICATION_JSON));
    }

    private String receipt(String contract, int amount) {
        return movementReceipt(contract, "0x" + "0".repeat(40), FARMER, ADMIN, 5, amount);
    }

    private String movementReceipt(String contract, String from, String to, String operator, int token, int amount) {
        String data = "0x" + "%064x".formatted(token) + "%064x".formatted(amount);
        String topicOperator = "0x" + "0".repeat(24) + operator.substring(2);
        String topicFrom = "0x" + "0".repeat(24) + from.substring(2);
        String topicTo = "0x" + "0".repeat(24) + to.substring(2);
        return "{\"transactionHash\":\"" + HASH + "\",\"status\":\"0x1\",\"logs\":[{\"address\":\""
                + contract + "\",\"topics\":[\"" + TOPIC + "\",\"" + topicOperator + "\",\""
                + topicFrom + "\",\"" + topicTo + "\"],\"data\":\"" + data + "\"}]}";
    }
}
