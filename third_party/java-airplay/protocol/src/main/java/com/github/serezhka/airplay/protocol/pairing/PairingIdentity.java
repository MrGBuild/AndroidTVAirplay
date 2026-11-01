package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

import java.security.KeyPair;


/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 * <p>
 * Not the HKP controller identity saved by the cast client ({@code pairing_id} + seed) —
 * that lives in the client credential store.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    public static PairingIdentity fromSeed(byte[] seed) {
        if (seed == null || seed.length != 32) throw new IllegalArgumentException("Identity seed must have 32 bytes");
        var spec = new net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec(seed, net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName("Ed25519"));
        var privateKey = new net.i2p.crypto.eddsa.EdDSAPrivateKey(spec);
        var publicKey = new net.i2p.crypto.eddsa.EdDSAPublicKey(new net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(spec.getA(), spec.getParams()));
        return new PairingIdentity(new KeyPair(publicKey, privateKey));
    }
    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    public String publicKeyHex() {
        StringBuilder hex = new StringBuilder(); for (byte b : publicKey()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return hex.toString();
    }

    KeyPair keyPair() {
        return keyPair;
    }
}
