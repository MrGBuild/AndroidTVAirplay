package com.mrgbuild.tvairplay;
import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
/** Persistent receiver identity, encrypted locally; never exported or advertised as a secret. */
final class PairingIdentityStore {
    static byte[] load(Context context) throws Exception {
        var prefs=context.getSharedPreferences("receiver_security",Context.MODE_PRIVATE);
        String encoded=prefs.getString("encryptedIdentity",null);
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        String alias="tvairplay.identity.v1";
        SecretKey key=(SecretKey)store.getKey(alias,null);
        if(encoded!=null){
            if(key==null)throw new GeneralSecurityException("Receiver identity key unavailable");
            String[] p=encoded.split(":");if(p.length!=2)throw new GeneralSecurityException("Invalid receiver identity");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));
            byte[] seed=cipher.doFinal(Base64.decode(p[1],Base64.NO_WRAP));if(seed.length!=32)throw new GeneralSecurityException("Invalid receiver identity");return seed;
        }
        if(key==null){KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());key=gen.generateKey();}
        byte[] seed=new byte[32];new SecureRandom().nextBytes(seed);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        String value=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(seed),Base64.NO_WRAP);
        if(!prefs.edit().putString("encryptedIdentity",value).commit())throw new java.io.IOException("Identity could not be saved");return seed;
    }
}
