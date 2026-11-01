package com.mrgbuild.engine;
import com.dd.plist.*;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.protocol.media.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineWireTest {
    static final String PASSWORD="synthetic-test-password";
    EngineReceiver receiver;
    @BeforeEach void start() throws Exception {
        receiver=new EngineReceiver("Wire Test Receiver","02:11:22:33:44:55",PASSWORD,new byte[32],new Playback(){
            public void onVideoFormat(VideoStreamInfo i){} public void onVideo(byte[] b){} public void onVideoSrcDisconnect(){}
            public void onAudioFormat(AudioStreamInfo i){} public void onAudio(byte[] b){} public void onAudioSrcDisconnect(){}
        });receiver.startWithoutDiscoveryForTest();
    }
    @AfterEach void close(){receiver.close();}
    static class Response {
        int code;String protocol;Map<String,String> headers=new HashMap<>();byte[] body;
    }
    static class Wire implements AutoCloseable {
        final Socket socket;final InputStream in;final OutputStream out;int seq;
        Wire(int port)throws Exception {socket=new Socket("127.0.0.1",port);socket.setSoTimeout(4000);in=socket.getInputStream();out=socket.getOutputStream();}
        Response send(String method,String uri,byte[] body,String auth)throws Exception {
            return send(method,uri,body,auth,"RTSP/1.0");
        }
        Response send(String method,String uri,byte[] body,String auth,String protocol)throws Exception {
            String head=method+" "+uri+" "+protocol+"\r\nCSeq: "+(++seq)+"\r\nActive-Remote: test-session\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: "+body.length+"\r\n"+(auth==null?"":"Authorization: "+auth+"\r\n")+"\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
            String status=line();assertNotNull(status);Response r=new Response();r.protocol=status.split(" ")[0];r.code=Integer.parseInt(status.split(" ")[1]);
            for(String h;(h=line())!=null&&!h.isEmpty();){int at=h.indexOf(':');r.headers.put(h.substring(0,at).toLowerCase(Locale.ROOT),h.substring(at+1).trim());}
            int length=Integer.parseInt(r.headers.getOrDefault("content-length","0"));assertTrue(length>=0&&length<=2*1024*1024);r.body=in.readNBytes(length);assertEquals(length,r.body.length);return r;
        }
        String line()throws Exception {ByteArrayOutputStream b=new ByteArrayOutputStream();for(int v;(v=in.read())!=-1;){if(v=='\n')return b.toString("US-ASCII").replaceFirst("\r$","");b.write(v);if(b.size()>16384)throw new IOException("oversized header");}return null;}
        public void close()throws Exception{socket.close();}
    }
    static String auth(Response challenge,String method,String uri,String password)throws Exception {
        var m=java.util.regex.Pattern.compile("nonce=\"([^\"]+)\"").matcher(challenge.headers.get("www-authenticate"));assertTrue(m.find());String nonce=m.group(1);
        return "Digest username=\"AirPlay\", realm=\"raop\", nonce=\""+nonce+"\", uri=\""+uri+"\", response=\""+md5(md5("AirPlay:raop:"+password)+":"+nonce+":"+md5(method+":"+uri))+"\"";
    }
    static String md5(String s)throws Exception {StringBuilder h=new StringBuilder();for(byte b:MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)))h.append(String.format(Locale.ROOT,"%02x",b&255));return h.toString();}
    static byte[] setup()throws Exception {NSDictionary d=new NSDictionary();d.put("ekey",new byte[72]);d.put("eiv",new byte[16]);return BinaryPropertyListWriter.writeToArray(d);}
    @Test void advertisesRealMirrorCapabilitiesAndConsistentIdentity()throws Exception {
        try(Wire w=new Wire(receiver.port())){
            var r=w.send("GET","/info",new byte[0],null);assertEquals(200,r.code);
            var info=(NSDictionary)PropertyListParser.parse(r.body);
            long features=((NSNumber)info.objectForKey("features")).longValue();assertNotEquals(0,features&(1L<<7));assertNotEquals(0,features&(1L<<9));
            assertEquals("Wire Test Receiver",info.objectForKey("name").toString());
            var key=(NSData)info.objectForKey("pk");StringBuilder pk=new StringBuilder();for(byte b:key.bytes())pk.append(String.format(Locale.ROOT,"%02x",b&255));assertEquals(receiver.publicKey(),pk.toString());
            var paired=w.send("POST","/pair-setup",new byte[0],null);assertEquals(200,paired.code);assertArrayEquals(key.bytes(),paired.body);
            assertTrue(((NSNumber)info.objectForKey("password")).boolValue());
        }
    }
    @Test void missingAndIncorrectPasswordsCannotSetUpMedia()throws Exception {
        try(Wire w=new Wire(receiver.port())){var challenge=w.send("SETUP","rtsp://receiver/session",setup(),null);assertEquals(401,challenge.code);
            var wrong=w.send("SETUP","rtsp://receiver/session",setup(),auth(challenge,"SETUP","rtsp://receiver/session","incorrect"));assertEquals(401,wrong.code);}
    }
    @Test void earlyRequestsAndPasswordChallengesAppearWithoutSenderSecrets()throws Exception {
        try(Wire w=new Wire(receiver.port())){
            assertEquals(200,w.send("GET","/info",new byte[0],null).code);
            assertEquals(401,w.send("SETUP","rtsp://receiver/private-user-url",setup(),null).code);
            String history=receiver.connectionDiagnostics();
            assertTrue(history.contains("TCP opened"));assertTrue(history.contains("GET /info"));
            assertTrue(history.contains("Response 401"));assertFalse(history.contains("private-user-url"));
            assertFalse(history.contains(PASSWORD));assertFalse(history.contains("nonce="));
        }
    }
    @Test void mirroringSetupKeepsTheLiveTimingPortAndAcceptsVideoConnections()throws Exception {
        try(Wire w=new Wire(receiver.port())){
            String uri="rtsp://receiver/session";var challenge=w.send("SETUP",uri,setup(),null);
            var initial=w.send("SETUP",uri,setup(),auth(challenge,"SETUP",uri,PASSWORD));assertEquals(200,initial.code);
            int timing=((NSNumber)((NSDictionary)PropertyListParser.parse(initial.body)).objectForKey("timingPort")).intValue();
            NSDictionary video=new NSDictionary(),stream=new NSDictionary();stream.put("type",110);stream.put("streamConnectionID",12345L);
            video.put("streams",new NSArray(stream));
            var response=w.send("SETUP",uri,BinaryPropertyListWriter.writeToArray(video),null);assertEquals(200,response.code);
            var result=(NSDictionary)PropertyListParser.parse(response.body);
            assertEquals(timing,((NSNumber)result.objectForKey("timingPort")).intValue());assertTrue(timing>0);
            var streams=(NSArray)result.objectForKey("streams");int port=((NSNumber)((NSDictionary)streams.objectAtIndex(0)).objectForKey("dataPort")).intValue();
            try(Socket media=new Socket("127.0.0.1",port)){assertTrue(media.isConnected());}
        }
    }
    @Test void handlerFailureIsVisibleEvenWithoutACompletedResponse()throws Exception {
        try(Wire w=new Wire(receiver.port())){
            String uri="/stream";var challenge=w.send("SETUP",uri,setup(),null);
            assertEquals(200,w.send("SETUP",uri,setup(),auth(challenge,"SETUP",uri,PASSWORD)).code);
            w.out.write("SETUP /stream RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 3\r\n\r\nbad".getBytes(StandardCharsets.US_ASCII));w.out.flush();
            assertEquals(-1,w.in.read());
            assertTrue(receiver.connectionDiagnostics().contains("Protocol handler failed"));
        }
    }
    @Test void correctPasswordStartsTimingNegotiation()throws Exception {
        try(Wire w=new Wire(receiver.port())){String uri="rtsp://receiver/session";var challenge=w.send("SETUP",uri,setup(),null);
            var accepted=w.send("SETUP",uri,setup(),auth(challenge,"SETUP",uri,PASSWORD));assertEquals(200,accepted.code);
            var d=(NSDictionary)PropertyListParser.parse(accepted.body);int port=((NSNumber)d.objectForKey("timingPort")).intValue();assertTrue(port>0);assertEquals(receiver.port(),((NSNumber)d.objectForKey("eventPort")).intValue());
            try(DatagramSocket socket=new DatagramSocket()){
                socket.setSoTimeout(3000);byte[] query=new byte[32];query[0]=(byte)0x80;query[1]=(byte)0xd2;Arrays.fill(query,24,32,(byte)0x42);
                socket.send(new DatagramPacket(query,32,InetAddress.getLoopbackAddress(),port));byte[] data=new byte[64];DatagramPacket packet=new DatagramPacket(data,data.length);socket.receive(packet);
                assertEquals(32,packet.getLength());assertEquals(0xd3,data[1]&255);assertArrayEquals(Arrays.copyOfRange(query,24,32),Arrays.copyOfRange(data,8,16));
            }
        }
    }
    @Test void credentialsCannotBeReplayedOnAnotherConnection()throws Exception {
        String authorization;try(Wire w=new Wire(receiver.port())){var c=w.send("SETUP","/stream",setup(),null);authorization=auth(c,"SETUP","/stream",PASSWORD);}
        try(Wire w=new Wire(receiver.port())){assertEquals(401,w.send("SETUP","/stream",setup(),authorization).code);}
    }
    @Test void shutdownClosesExistingControlSockets()throws Exception {
        try(Wire w=new Wire(receiver.port())){assertEquals(200,w.send("OPTIONS","*",new byte[0],null).code);receiver.close();assertEquals(-1,w.in.read());}
    }
    @Test void malformedPublicHandshakeIsRejected()throws Exception {
        try(Wire w=new Wire(receiver.port())){assertEquals(400,w.send("POST","/pair-verify",new byte[2],null).code);assertEquals(400,w.send("POST","/fp-setup",new byte[16],null).code);}
    }
    @Test void rtpSequenceWrapAndLossDoNotPermanentlyStallAudio(){
        assertTrue(com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler.newer(0,65535));
        assertTrue(com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler.newer(30,20));
        assertFalse(com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler.newer(20,20));
        assertFalse(com.github.serezhka.airplay.server.internal.handler.audio.AudioHandler.newer(19,20));
    }
}
