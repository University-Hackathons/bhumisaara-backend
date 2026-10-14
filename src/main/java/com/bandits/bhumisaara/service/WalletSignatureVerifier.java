package com.bandits.bhumisaara.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.math.ec.ECPoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.io.InputStream;
import java.util.Map;

@Service
public class WalletSignatureVerifier {
    private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
    private final RestClient rpc;
    private final ObjectMapper mapper;

    public WalletSignatureVerifier(RestClient.Builder builder, ObjectMapper mapper,
            @Value("${app.chain.rpc-url}") String rpcUrl) {
        this.rpc = builder.baseUrl(rpcUrl).build();
        this.mapper = mapper;
    }

    public boolean verify(String wallet, String message, String signature) {
        if (signature == null || !signature.matches("(?i)0x(?:[0-9a-f]{2})+")) return false;
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("\u0019Ethereum Signed Message:\n" + msg.length).getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[prefix.length + msg.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(msg, 0, input, prefix.length, msg.length);
        byte[] digest = keccak(input);
        String code = call("eth_getCode", List.of(wallet, "latest")).asText();
        if (!"0x".equals(code) && !"0x0".equals(code)) {
            // ERC-1271 isValidSignature(bytes32,bytes); supports deployed smart wallets.
            String sig = signature.substring(2);
            String data = "0x1626ba7e" + HexFormat.of().formatHex(digest)
                    + word(BigInteger.valueOf(64)) + word(BigInteger.valueOf(sig.length() / 2))
                    + sig + "0".repeat((64 - sig.length() % 64) % 64);
            try {
                String result = call("eth_call", List.of(Map.of("to", wallet, "data", data), "latest")).asText();
                if (result.toLowerCase().startsWith("0x1626ba7e")) return true;
            } catch (IllegalStateException ignored) { /* Try ERC-6492 validation below. */ }
        }
        if (signature.toLowerCase().endsWith("6492".repeat(16))) {
            try {
                String bytecode;
                try (InputStream stream = getClass().getResourceAsStream("/chain/erc6492-validator.hex")) {
                    bytecode = new String(stream.readAllBytes(), StandardCharsets.US_ASCII).trim();
                }
                String sigHex = signature.substring(2);
                String args = word(new BigInteger(wallet.substring(2), 16)) + HexFormat.of().formatHex(digest)
                        + word(BigInteger.valueOf(96)) + word(BigInteger.valueOf(sigHex.length() / 2))
                        + sigHex + "0".repeat((64 - sigHex.length() % 64) % 64);
                String result = call("eth_call", List.of(Map.of("data", "0x" + bytecode + args), "latest")).asText();
                return !new BigInteger(result.substring(2), 16).equals(BigInteger.ZERO);
            } catch (Exception ex) { return false; }
        }
        if (signature.length() != 132) return false;
        try {
        byte[] sig = HexFormat.of().parseHex(signature.substring(2));
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 32, 64));
        int recovery = (sig[64] & 255) - 27;
        if (recovery >= 4) recovery -= 4;
        BigInteger n = CURVE.getN();
        if (recovery < 0 || recovery > 3 || r.signum() <= 0 || s.signum() <= 0
                || r.compareTo(n) >= 0 || s.compareTo(n) >= 0) return false;
        BigInteger x = r.add(n.multiply(BigInteger.valueOf(recovery / 2)));
        if (x.compareTo(CURVE.getCurve().getField().getCharacteristic()) >= 0) return false;
        byte[] encoded = new byte[33]; encoded[0] = (byte) (recovery % 2 == 0 ? 2 : 3);
        byte[] xb = rBytes(x); System.arraycopy(xb, 0, encoded, 1, 32);
        ECPoint point = CURVE.getCurve().decodePoint(encoded);
        if (!point.multiply(n).isInfinity()) return false;
        BigInteger e = new BigInteger(1, digest);
        ECPoint pub = point.multiply(s).subtract(CURVE.getG().multiply(e)).multiply(r.modInverse(n)).normalize();
        byte[] address = keccak(java.util.Arrays.copyOfRange(pub.getEncoded(false), 1, 65));
        return wallet.equalsIgnoreCase("0x" + HexFormat.of().formatHex(java.util.Arrays.copyOfRange(address, 12, 32)));
        } catch (RuntimeException ex) { return false; }
    }

    private JsonNode call(String method, List<?> params) {
        try {
            String body = rpc.post().body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
                    .retrieve().body(String.class);
            JsonNode result = mapper.readTree(body);
            if (result.hasNonNull("error") || !result.has("result")) throw new IllegalStateException("RPC error");
            return result.get("result");
        } catch (Exception ex) { throw new IllegalStateException("Wallet signature RPC check failed", ex); }
    }
    private static byte[] keccak(byte[] input) {
        KeccakDigest d = new KeccakDigest(256); d.update(input, 0, input.length);
        byte[] out = new byte[32]; d.doFinal(out, 0); return out;
    }
    private static byte[] rBytes(BigInteger n) {
        byte[] raw = n.toByteArray(); byte[] out = new byte[32];
        System.arraycopy(raw, Math.max(0, raw.length - 32), out, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return out;
    }
    private static String word(BigInteger n) { return String.format("%064x", n); }
}
