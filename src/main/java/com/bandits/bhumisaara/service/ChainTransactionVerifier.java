package com.bandits.bhumisaara.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.bandits.bhumisaara.entity.RecordedChainTransactionEntity;
import com.bandits.bhumisaara.repository.RecordedChainTransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Validates the actual ERC-1155 movement, rather than trusting a submitted hash. */
@Service
public class ChainTransactionVerifier {
    private static final String TRANSFER_SINGLE = "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62";
    private static final String TRANSFER_BATCH = "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";
    private final RestClient client;
    private final RecordedChainTransactionRepository recorded;
    private final ObjectMapper mapper;
    private final String contract;
    private final long chainId;

    public ChainTransactionVerifier(RestClient.Builder builder, ObjectMapper mapper, RecordedChainTransactionRepository recorded,
            @Value("${app.chain.rpc-url}") String rpcUrl,
            @Value("${app.chain.contract-address}") String contract,
            @Value("${app.chain.id:80002}") long chainId) {
        this.client = builder.baseUrl(rpcUrl).build();
        this.mapper = mapper;
        this.recorded = recorded;
        if (!contract.matches("(?i)0x[0-9a-f]{40}"))
            throw new IllegalArgumentException("Invalid configured chain contract address");
        this.contract = contract.toLowerCase();
        this.chainId = chainId;
    }

    public void verifyMint(String hash, String operator, String recipient, String tokenId, long amount) {
        if (operator == null || !operator.matches("(?i)0x[0-9a-f]{40}"))
            throw new IllegalArgumentException("Issuing wallet is not linked");
        verify(hash, operator, ZERO, recipient, tokenId, amount);
    }

    public void verifyTransfer(String hash, String sender, String recipient, String tokenId, long amount) {
        verify(hash, null, sender, recipient, tokenId, amount);
    }

    public void verifyBurn(String hash, String sender, String tokenId, long amount) {
        verify(hash, null, sender, ZERO, tokenId, amount);
    }

    private void verify(String hash, String operator, String from, String to, String tokenId, long amount) {
        if (hash == null || !hash.matches("(?i)0x[0-9a-f]{64}") || from == null || to == null
                || tokenId == null || amount <= 0) {
            throw new IllegalArgumentException("Invalid blockchain transaction details");
        }
        if (recorded.existsById(hash.toLowerCase()) || recorded.existsInLegacyLedgers(hash.toLowerCase()))
            throw new IllegalStateException("Transaction already recorded");
        JsonNode chain = rpc("eth_chainId", List.of());
        if (hex(chain.asText()).longValueExact() != chainId) {
            throw new IllegalStateException("RPC is connected to the wrong chain");
        }
        JsonNode tx = rpc("eth_getTransactionByHash", List.of(hash));
        JsonNode receipt = rpc("eth_getTransactionReceipt", List.of(hash));
        if (tx.isNull() || receipt.isNull() || !hash.equalsIgnoreCase(tx.path("hash").asText())
                || !hash.equalsIgnoreCase(receipt.path("transactionHash").asText())
                || !"0x1".equalsIgnoreCase(receipt.path("status").asText())) {
            throw new IllegalArgumentException("Transaction is missing or failed");
        }
        // EIP-4337 smart wallets can be represented by the event's operator/from,
        // while the outer transaction sender is a bundler. The contract event is authoritative.
        BigInteger expectedId = new BigInteger(tokenId);
        BigInteger expectedAmount = BigInteger.valueOf(amount);
        int movements = 0;
        boolean matched = false;
        for (JsonNode log : receipt.path("logs")) {
            if (!contract.equalsIgnoreCase(log.path("address").asText())) continue;
            JsonNode topics = log.path("topics");
            if (!topics.isArray() || topics.size() == 0) continue;
            if (TRANSFER_BATCH.equalsIgnoreCase(topics.get(0).asText())) {
                throw new IllegalArgumentException("Batch token movement cannot be recorded as a single transfer");
            }
            if (!TRANSFER_SINGLE.equalsIgnoreCase(topics.get(0).asText())) continue;
            movements++;
            if (topics.size() != 4 || !topics.get(1).asText().matches("(?i)0x[0-9a-f]{64}")
                    || !topics.get(2).asText().matches("(?i)0x[0-9a-f]{64}")
                    || !topics.get(3).asText().matches("(?i)0x[0-9a-f]{64}")) continue;
            String data = log.path("data").asText("");
            if (!data.matches("(?i)0x[0-9a-f]{128}")) continue;
            String eventOperator = address(topics.get(1).asText());
            String eventFrom = address(topics.get(2).asText());
            String eventTo = address(topics.get(3).asText());
            matched = (operator == null || operator.equalsIgnoreCase(eventOperator))
                    && from.equalsIgnoreCase(eventFrom) && to.equalsIgnoreCase(eventTo)
                    && expectedId.equals(new BigInteger(data.substring(2, 66), 16))
                    && expectedAmount.equals(new BigInteger(data.substring(66), 16));
        }
        if (movements != 1 || !matched) {
            throw new IllegalArgumentException("Transaction does not contain the expected ERC-1155 movement");
        }
        RecordedChainTransactionEntity used = new RecordedChainTransactionEntity();
        used.setHash(hash.toLowerCase());
        recorded.saveAndFlush(used);
    }

    private JsonNode rpc(String method, List<?> params) {
        try {
            String body = client.post().body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
                    .retrieve().body(String.class);
            JsonNode response = mapper.readTree(body);
            if (response.hasNonNull("error") || !response.has("result")) throw new IllegalStateException("RPC error: " + method);
            return response.get("result");
        } catch (Exception e) {
            throw new IllegalStateException("Could not verify blockchain transaction", e);
        }
    }

    private static BigInteger hex(String value) { return new BigInteger(value.substring(2), 16); }
    private static String address(String topic) { return "0x" + topic.substring(topic.length() - 40); }
}
