package com.bandits.bhumisaara.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WalletSignatureVerifierTest {
    private MockRestServiceServer server;
    private WalletSignatureVerifier verifier;

    @BeforeEach void setup() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new WalletSignatureVerifier(builder, new ObjectMapper(), "http://unused.example");
    }

    @Test void verifiesRealEoaSignatureAndRejectsWrongAddress() {
        String message = "Bhumisaara challenge";
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] prefix = ("\u0019Ethereum Signed Message:\n" + bytes.length).getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(bytes, 0, input, prefix.length, bytes.length);
        byte[] digest = keccak(input);
        var curve = SECNamedCurves.getByName("secp256k1");
        var domain = new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
        var signer = new ECDSASigner();
        signer.init(true, new ECPrivateKeyParameters(BigInteger.ONE, domain));
        BigInteger[] rs = signer.generateSignature(digest);
        byte[] pub = curve.getG().getEncoded(false);
        byte[] addr = keccak(Arrays.copyOfRange(pub, 1, 65));
        String address = "0x" + HexFormat.of().formatHex(Arrays.copyOfRange(addr, 12, 32));
        String sigBody = "%064x%064x".formatted(rs[0], rs[1]);
        boolean valid = false;
        respond("eth_getCode", "\"0x\"");
        respond("eth_getCode", "\"0x\"");
        respond("eth_getCode", "\"0x\"");
        for (int recovery = 27; recovery <= 28; recovery++) {
            if (verifier.verify(address, message, "0x" + sigBody + "%02x".formatted(recovery))) valid = true;
        }
        assertTrue(valid);
        assertFalse(verifier.verify("0x" + "f".repeat(40), message, "0x" + sigBody + "1b"));
    }

    @Test void verifiesDeployedSmartWalletViaErc1271() {
        respond("eth_getCode", "\"0x6001\"");
        respond("eth_call", "\"0x1626ba7e" + "0".repeat(56) + "\"");
        assertTrue(verifier.verify("0x" + "1".repeat(40), "challenge", "0x" + "a".repeat(130)));
        server.verify();
    }

    @Test void verifiesCounterfactualSmartWalletViaErc6492() {
        respond("eth_getCode", "\"0x\"");
        respond("eth_call", "\"0x" + "0".repeat(63) + "1\"");
        assertTrue(verifier.verify("0x" + "1".repeat(40), "challenge", "0x" + "a".repeat(130) + "6492".repeat(16)));
        server.verify();
    }

    private void respond(String method, String result) {
        server.expect(once(), requestTo("http://unused.example"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"method\":\"" + method + "\"")))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}", MediaType.APPLICATION_JSON));
    }
    private byte[] keccak(byte[] input) {
        KeccakDigest d = new KeccakDigest(256); d.update(input, 0, input.length);
        byte[] out = new byte[32]; d.doFinal(out, 0); return out;
    }
}
