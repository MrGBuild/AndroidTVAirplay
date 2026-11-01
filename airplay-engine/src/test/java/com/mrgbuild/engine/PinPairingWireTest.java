package com.mrgbuild.engine;

import com.dd.plist.*;
import org.bouncycastle.crypto.agreement.srp.*;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.junit.jupiter.api.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.mrgbuild.engine.EngineWireTest.*;

class PinPairingWireTest {
    EngineWireTest host=new EngineWireTest();
    @BeforeEach void start()throws Exception{host.start();}
    @AfterEach void stop(){host.close();}
    static byte[] bytes(BigInteger n){byte[] b=n.toByteArray();return b.length>1&&b[0]==0?Arrays.copyOfRange(b,1,b.length):b;}
    static byte[] hash(String algorithm,byte[]... chunks)throws Exception{MessageDigest d=MessageDigest.getInstance(algorithm);for(byte[] b:chunks)d.update(b);return d.digest();}
    static byte[] data(NSDictionary d,String key){return ((NSData)d.objectForKey(key)).bytes();}
    static NSDictionary parse(Response r)throws Exception{assertEquals(200,r.code);return (NSDictionary)PropertyListParser.parse(r.body);}
    static NSDictionary begin(){NSDictionary d=new NSDictionary();d.put("method","pin");d.put("user","AABBCCDDEEFF0011");return d;}
    static class Client {
        byte[] key,proof,publicKey;
        Client(NSDictionary reply,String password)throws Exception{
            this(reply,password,"AABBCCDDEEFF0011".getBytes(StandardCharsets.UTF_8));
        }
        Client(NSDictionary reply,String password,byte[] identity)throws Exception{
            var group=SRP6StandardGroups.rfc5054_2048;
            SRP6Client client=new SRP6Client();client.init(group,new SHA1Digest(),new SecureRandom());
            publicKey=bytes(client.generateClientCredentials(data(reply,"salt"),identity,password.getBytes(StandardCharsets.UTF_8)));
            byte[] s=bytes(client.calculateSecret(new BigInteger(1,data(reply,"pk"))));
            byte[] k1=hash("SHA-1",s,new byte[4]),k2=hash("SHA-1",s,new byte[]{0,0,0,1});key=new byte[40];System.arraycopy(k1,0,key,0,20);System.arraycopy(k2,0,key,20,20);
            byte[] xor=bytes(new BigInteger(1,hash("SHA-1",bytes(group.getN()))).xor(new BigInteger(1,hash("SHA-1",bytes(group.getG())))));
            proof=hash("SHA-1",xor,bytes(new BigInteger(1,hash("SHA-1",identity))),data(reply,"salt"),publicKey,bytes(new BigInteger(1,data(reply,"pk"))),key);
        }
        NSDictionary proof(){NSDictionary d=new NSDictionary();d.put("pk",publicKey);d.put("proof",proof);return d;}
        byte[] aesKey()throws Exception{return Arrays.copyOf(hash("SHA-512","Pair-Setup-AES-Key".getBytes(StandardCharsets.UTF_8),key),16);}
        byte[] iv(int increment)throws Exception{byte[] iv=Arrays.copyOf(hash("SHA-512","Pair-Setup-AES-IV".getBytes(StandardCharsets.UTF_8),key),16);iv[15]+=increment;return iv;}
    }
    Response post(Wire wire,NSDictionary d,String authorization)throws Exception{return wire.send("POST","/pair-setup-pin",BinaryPropertyListWriter.writeToArray(d),authorization);}
    Client negotiate(Wire wire,String password)throws Exception{
        var challenge=post(wire,begin(),null);assertEquals(401,challenge.code);
        var first=parse(post(wire,begin(),auth(challenge,"POST","/pair-setup-pin",PASSWORD)));
        return new Client(first,password);
    }
    @Test void fullPasswordSrpExchangeAuthenticatesBothProofsAndExchangesReceiverKey()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){
            Client client=negotiate(wire,PASSWORD);var second=parse(post(wire,client.proof(),null));
            assertArrayEquals(hash("SHA-1",client.publicKey,client.proof,client.key),data(second,"proof"));
            Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding");encrypt.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(client.aesKey(),"AES"),new GCMParameterSpec(128,client.iv(1)));
            byte[] blob=encrypt.doFinal(new byte[32]);NSDictionary third=new NSDictionary();third.put("epk",Arrays.copyOf(blob,32));third.put("authTag",Arrays.copyOfRange(blob,32,48));
            var finalReply=parse(post(wire,third,null));byte[] encrypted=new byte[48];System.arraycopy(data(finalReply,"epk"),0,encrypted,0,32);System.arraycopy(data(finalReply,"authTag"),0,encrypted,32,16);
            Cipher decrypt=Cipher.getInstance("AES/GCM/NoPadding");decrypt.init(Cipher.DECRYPT_MODE,new SecretKeySpec(client.aesKey(),"AES"),new GCMParameterSpec(128,client.iv(2)));
            assertEquals(host.receiver.publicKey(),HexFormat.of().formatHex(decrypt.doFinal(encrypted)));
            assertEquals(200,wire.send("SETUP","/stream",setup(),null).code);
        }
    }
    @Test void incorrectSrpPasswordDoesNotProduceServerProof()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){Client client=negotiate(wire,"wrong-password");assertEquals(403,post(wire,client.proof(),null).code);assertEquals(400,post(wire,client.proof(),null).code);}
    }
    @Test void pairPinStartExistsAndStillRequiresPassword()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){var challenge=wire.send("POST","/pair-pin-start",new byte[0],null);assertEquals(401,challenge.code);assertEquals(200,wire.send("POST","/pair-pin-start",new byte[0],auth(challenge,"POST","/pair-pin-start",PASSWORD)).code);}
    }
    @Test void zeroPublicKeyAndOutOfOrderMessagesAreRejected()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){negotiate(wire,PASSWORD);NSDictionary bad=new NSDictionary();bad.put("pk",new byte[256]);bad.put("proof",new byte[20]);assertNotEquals(200,post(wire,bad,null).code);}
    }
    @Test void unauthenticatedEncryptedKeyCannotCompletePairing()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){
            Client client=negotiate(wire,PASSWORD);parse(post(wire,client.proof(),null));
            NSDictionary forged=new NSDictionary();forged.put("epk",new byte[32]);forged.put("authTag",new byte[16]);
            assertEquals(403,post(wire,forged,null).code);
            assertEquals(400,post(wire,forged,null).code);
        }
    }
    @Test void httpPairingCanContinueToPairVerifyWithoutSwitchingProtocol()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){
            var setup=wire.send("POST","/pair-setup",new byte[0],null,"HTTP/1.1");assertEquals(200,setup.code);assertEquals("HTTP/1.1",setup.protocol);assertEquals(32,setup.body.length);
            byte[] body=new byte[68];body[0]=1;Arrays.fill(body,4,36,(byte)9);Arrays.fill(body,36,68,(byte)1);
            var verify=wire.send("POST","/pair-verify",body,null,"HTTP/1.1");assertEquals(200,verify.code);assertEquals("HTTP/1.1",verify.protocol);assertEquals(96,verify.body.length);
        }
    }
    @Test void opaqueTextAndBinaryIdentitiesRetainTheirExactSrpBytes()throws Exception{
        for(Object identity:new Object[]{"phone:test_id@network!?","Téléphone: test",new byte[]{0,1,(byte)255,':','_'}}){
            byte[] raw=identity instanceof byte[] bytes?bytes:identity.toString().getBytes(StandardCharsets.UTF_8);
            try(Wire wire=new Wire(host.receiver.port())){
                NSDictionary request=begin();request.put("user",identity);
                var challenge=post(wire,request,null);assertEquals(401,challenge.code);
                var first=parse(post(wire,request,auth(challenge,"POST","/pair-setup-pin",PASSWORD)));
                Client client=new Client(first,PASSWORD,raw);
                var proof=parse(post(wire,client.proof(),null));
                assertArrayEquals(hash("SHA-1",client.publicKey,client.proof,client.key),data(proof,"proof"));
            }
        }
    }
    @Test void malformedIdentityReportsSafeReasonWithoutLeakingItsValue()throws Exception{
        try(Wire wire=new Wire(host.receiver.port())){
            NSDictionary request=begin();request.put("user",new byte[257]);
            var challenge=post(wire,request,null);
            assertEquals(400,post(wire,request,auth(challenge,"POST","/pair-setup-pin",PASSWORD)).code);
            assertTrue(host.receiver.connectionDiagnostics().contains("PIN rejected: identity length outside limits"));
            assertFalse(host.receiver.connectionDiagnostics().contains(PASSWORD));
        }
    }
}
