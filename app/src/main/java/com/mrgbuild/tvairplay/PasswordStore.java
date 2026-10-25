package com.mrgbuild.tvairplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Password is encrypted at rest; the AES key stays in AndroidKeyStore. */
final class PasswordStore {
    private static final String ALIAS = "tvairplay.receiver-password.v1";
    private final SharedPreferences preferences;
    PasswordStore(Context context) { preferences = context.getSharedPreferences("receiver_security", Context.MODE_PRIVATE); }
    String load() throws GeneralSecurityException, IOException {
        String encoded = preferences.getString("encryptedPassword", null);
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 2) throw new GeneralSecurityException("Invalid password storage");
            KeyStore store = keyStore();
            SecretKey key = (SecretKey)store.getKey(ALIAS, null);
            if (key == null) throw new GeneralSecurityException("Password key unavailable");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            String password = new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
            validate(password);
            return password;
        } catch (IllegalArgumentException e) { throw new GeneralSecurityException("Password storage could not be read", e); }
    }
    void save(String password) throws GeneralSecurityException, IOException {
        validate(password);
        KeyStore store = keyStore();
        SecretKey key = (SecretKey)store.getKey(ALIAS, null);
        if (key == null) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            key = generator.generateKey();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key);
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!preferences.edit().putString("encryptedPassword", encoded).commit()) throw new IOException("Password could not be saved");
    }
    static void validate(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) throw new IllegalArgumentException("Use 8–64 characters");
        for (int i = 0; i < password.length(); i++) {
            if (password.charAt(i) < 33 || password.charAt(i) > 126) throw new IllegalArgumentException("Use letters, numbers or symbols without spaces");
        }
    }
    private KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); return store;
    }
}
