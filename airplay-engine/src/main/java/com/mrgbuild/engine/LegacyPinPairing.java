package com.mrgbuild.engine;

import com.dd.plist.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.agreement.srp.*;
import org.bouncycastle.crypto.digests.SHA1Digest;

/** Legacy AirPlay SRP-6a (RFC5054 2048-bit group, SHA-1, Apple session-key expansion).
 * Owned implementation; this is separate from HomeKit/TLV pairing.
 */
final class LegacyPinPairing {
    private static final BigInteger N = SRP6StandardGroups.rfc5054_2048.getN();
    private static final BigInteger G = SRP6StandardGroups.rfc5054_2048.getG();
    private final String password;
    private final byte[] receiverKey;
    private SRP6Server srp;
    private byte[] salt, user, serverPublic, sessionKey;
    private int step;
    private long expires;

    LegacyPinPairing(String password, byte[] receiverKey) {
        this.password = password; this.receiverKey = receiverKey.clone();
    }
    NSDictionary exchange(NSDictionary request) throws Exception {
        if (request.containsKey("method")) {
            reset();
            if (!"pin".equals(request.objectForKey("method").toString())) throw new RequestError("unsupported method");
            NSObject username=request.objectForKey("user");
            // SRP identities are opaque byte strings, not receiver names or account names.
            // Preserve punctuation, Unicode and binary identities exactly; do not normalize.
            if (username instanceof NSString text) user=text.getContent().getBytes(StandardCharsets.UTF_8);
            else if (username instanceof NSData bytes) user=bytes.bytes().clone();
            else throw new RequestError("unsupported identity type");
            if(user.length==0||user.length>256)throw new RequestError("identity length outside limits");
            salt=new byte[16];new SecureRandom().nextBytes(salt);
            SRP6VerifierGenerator verifier=new SRP6VerifierGenerator();verifier.init(N,G,new SHA1Digest());
            BigInteger v=verifier.generateVerifier(salt,user,password.getBytes(StandardCharsets.UTF_8));
            srp=new SRP6Server();srp.init(N,G,v,new SHA1Digest(),new SecureRandom());
            serverPublic=pad(srp.generateServerCredentials());
            expires=System.nanoTime()+60_000_000_000L;step=1;
            NSDictionary response=new NSDictionary();response.put("pk",serverPublic);response.put("salt",salt);return response;
        }
        if (step==0 || System.nanoTime()>expires) {reset();throw new RequestError("missing or expired exchange");}
        if (step==1) {
            byte[] clientPublic=data(request,"pk",1,256), proof=data(request,"proof",20,20);
            BigInteger a=new BigInteger(1,clientPublic);
            if(a.compareTo(N)>=0)throw new IllegalArgumentException();
            byte[] secret=unsigned(srp.calculateSecret(a));
            sessionKey=join(hash("SHA-1",secret,new byte[]{0,0,0,0}),hash("SHA-1",secret,new byte[]{0,0,0,1}));
            byte[] xor=unsigned(new BigInteger(1,hash("SHA-1",unsigned(N))).xor(new BigInteger(1,hash("SHA-1",unsigned(G)))));
            byte[] expected=hash("SHA-1",xor,unsigned(new BigInteger(1,hash("SHA-1",user))),salt,unsigned(a),unsigned(new BigInteger(1,serverPublic)),sessionKey);
            Arrays.fill(secret,(byte)0);
            if(!MessageDigest.isEqual(expected,proof)){reset();throw new GeneralSecurityException();}
            NSDictionary response=new NSDictionary();response.put("proof",hash("SHA-1",unsigned(a),proof,sessionKey));step=2;return response;
        }
        byte[] epk=data(request,"epk",32,32),tag=data(request,"authTag",16,16);
        byte[] key=Arrays.copyOf(hash("SHA-512","Pair-Setup-AES-Key".getBytes(StandardCharsets.UTF_8),sessionKey),16);
        byte[] iv=Arrays.copyOf(hash("SHA-512","Pair-Setup-AES-IV".getBytes(StandardCharsets.UTF_8),sessionKey),16);
        iv[15]++; // Legacy protocol increments the final octet for each direction.
        try {
            Cipher decrypt=Cipher.getInstance("AES/GCM/NoPadding");decrypt.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            byte[] peer=decrypt.doFinal(join(epk,tag));
            if(peer.length!=32)throw new GeneralSecurityException();
            Arrays.fill(peer,(byte)0);
            iv[15]++;
            Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding");encrypt.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            byte[] encrypted=encrypt.doFinal(receiverKey);
            NSDictionary response=new NSDictionary();response.put("epk",Arrays.copyOf(encrypted,32));response.put("authTag",Arrays.copyOfRange(encrypted,32,48));
            return response;
        } finally {Arrays.fill(key,(byte)0);reset();}
    }
    void reset(){if(sessionKey!=null)Arrays.fill(sessionKey,(byte)0);sessionKey=null;srp=null;step=0;}
    static final class RequestError extends IllegalArgumentException {
        RequestError(String reason){super(reason);}
    }
    private static byte[] data(NSDictionary d,String field,int min,int max){
        if(!(d.objectForKey(field) instanceof NSData value)||value.length()<min||value.length()>max)throw new IllegalArgumentException();return value.bytes();
    }
    private static byte[] unsigned(BigInteger n){byte[] b=n.toByteArray();return b.length>1&&b[0]==0?Arrays.copyOfRange(b,1,b.length):b;}
    private static byte[] pad(BigInteger n){byte[] b=unsigned(n),p=new byte[256];System.arraycopy(b,0,p,256-b.length,b.length);return p;}
    private static byte[] join(byte[]... chunks){int size=0;for(byte[] b:chunks)size+=b.length;byte[] r=new byte[size];int at=0;for(byte[] b:chunks){System.arraycopy(b,0,r,at,b.length);at+=b.length;}return r;}
    private static byte[] hash(String algorithm,byte[]... chunks)throws GeneralSecurityException{MessageDigest d=MessageDigest.getInstance(algorithm);for(byte[] b:chunks)d.update(b);return d.digest();}
}
