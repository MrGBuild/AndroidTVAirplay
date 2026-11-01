package com.mrgbuild.tvairplay;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.mrgbuild.engine.EngineReceiver;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public class AndroidReceiverTest {
    @Test public void mirrorFitsPortraitAndRotatesToLandscapeWithoutStretching()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            android.widget.FrameLayout root=new android.widget.FrameLayout(instrumentation.getTargetContext());
            MirrorSurfaceView view=new MirrorSurfaceView(instrumentation.getTargetContext());
            root.addView(view,new android.widget.FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER));
            int width=android.view.View.MeasureSpec.makeMeasureSpec(1920,android.view.View.MeasureSpec.EXACTLY);
            int height=android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY);
            view.setVideoAspectRatio(9f/16);root.measure(width,height);root.layout(0,0,1920,1080);
            assertEquals(608,view.getWidth());assertEquals(1080,view.getHeight());assertEquals(656,view.getLeft());
            view.setVideoAspectRatio(16f/9);root.measure(width,height);root.layout(0,0,1920,1080);
            assertEquals(1920,view.getWidth());assertEquals(1080,view.getHeight());assertEquals(0,view.getLeft());
            view.setVideoAspectRatio(19.5f/9);root.measure(width,height);root.layout(0,0,1920,1080);
            assertEquals(1920,view.getWidth());assertEquals(886,view.getHeight());assertEquals(97,view.getTop());
        });
    }
    @Test public void passwordPinSrpAndLegacyGcmRunOnAndroid()throws Exception{
        Playback sink=new Playback(){public void onVideoFormat(VideoStreamInfo i){}public void onVideo(byte[] b){}public void onVideoSrcDisconnect(){}public void onAudioFormat(AudioStreamInfo i){}public void onAudio(byte[] b){}public void onAudioSrcDisconnect(){}};
        try(EngineReceiver receiver=new EngineReceiver("Android PIN Test","02:11:22:33:44:55","synthetic-password",new byte[32],sink)){
            receiver.startWithoutDiscoveryForTest();
            try(Socket socket=new Socket("127.0.0.1",receiver.port())){
                socket.setSoTimeout(15000);
                String fixture="62706c6973743030d201020304566d6574686f6454757365725370696e5f101041414242434344444545464630303131080d14191d0000000000000101000000000000000500000000000000000000000000000030";
                byte[] body=new byte[fixture.length()/2];for(int i=0;i<body.length;i++)body[i]=(byte)Integer.parseInt(fixture.substring(2*i,2*i+2),16);
                String base="POST /pair-setup-pin RTSP/1.0\r\nCSeq: 1\r\nContent-Length: "+body.length+"\r\n";
                socket.getOutputStream().write((base+"\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
                String challenge=pinHeaders(socket.getInputStream());assertTrue(challenge.contains("401"));
                var nonce=java.util.regex.Pattern.compile("nonce=\"([^\"]+)\"").matcher(challenge);assertTrue(nonce.find());
                String response=pinMd5(pinMd5("AirPlay:raop:synthetic-password")+":"+nonce.group(1)+":"+pinMd5("POST:/pair-setup-pin"));
                String auth="Authorization: Digest username=\"AirPlay\", realm=\"raop\", nonce=\""+nonce.group(1)+"\", uri=\"/pair-setup-pin\", response=\""+response+"\"\r\n";
                socket.getOutputStream().write((base+auth+"\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
                String headers=pinHeaders(socket.getInputStream());assertTrue(headers,headers.contains("200 OK"));
                var length=java.util.regex.Pattern.compile("(?i)Content-Length: (\\d+)").matcher(headers);assertTrue(length.find());int size=Integer.parseInt(length.group(1));assertTrue(size>256&&size<2048);
                byte[] reply=new byte[size];int at=0;while(at<size){int n=socket.getInputStream().read(reply,at,size-at);assertTrue(n>0);at+=n;}
                assertEquals("bplist00",new String(reply,0,8,StandardCharsets.US_ASCII));
            }
        }
        // Legacy pairing uses a 16-byte GCM IV; check the Android provider explicitly.
        var key=new javax.crypto.spec.SecretKeySpec(new byte[16],"AES");var params=new javax.crypto.spec.GCMParameterSpec(128,new byte[16]);
        var enc=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");enc.init(javax.crypto.Cipher.ENCRYPT_MODE,key,params);byte[] encrypted=enc.doFinal(new byte[32]);
        var dec=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");dec.init(javax.crypto.Cipher.DECRYPT_MODE,key,params);assertArrayEquals(new byte[32],dec.doFinal(encrypted));
    }
    private static String pinHeaders(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();while(!b.toString("US-ASCII").endsWith("\r\n\r\n")){int n=in.read();assertTrue(n>=0);b.write(n);assertTrue(b.size()<8192);}return b.toString("US-ASCII");}
    private static String pinMd5(String text)throws Exception{StringBuilder s=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)))s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    @Test public void foregroundAppAdvertisesTheSavedNameOnAndroidWifi()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();var context=instrumentation.getTargetContext();
        new PasswordStore(context).save("synthetic-instrumentation-password");
        assertTrue(context.getSharedPreferences("MainActivity",0).edit().putString("receiverName","Android Fixture").commit());
        android.app.Activity activity=instrumentation.startActivitySync(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        String[] message={""};long deadline=System.nanoTime()+25_000_000_000L;
        try{
            while(System.nanoTime()<deadline){
                instrumentation.runOnMainSync(() -> {android.widget.TextView text=activity.getWindow().getDecorView().findViewWithTag("receiver-status");message[0]=text.getText().toString();});
                if(message[0].startsWith("Advertising"))break;Thread.sleep(200);
            }
            assertTrue(message[0],message[0].startsWith("Advertising")&&message[0].contains("Android Fixture"));
            instrumentation.runOnMainSync(() -> {
                android.widget.Button quality=activity.getWindow().getDecorView().findViewWithTag("mirror-quality");
                assertNotNull(quality);assertTrue(quality.getText().toString().startsWith("Mirroring quality: "));
            });
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    private byte[] asset(String name)throws Exception{
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);ByteArrayOutputStream data=new ByteArrayOutputStream()){
            byte[] block=new byte[4096];int n;while((n=input.read(block))!=-1)data.write(block,0,n);return data.toByteArray();
        }
    }
    @Test public void passwordAndIdentityRoundTripThroughAndroidKeystore()throws Exception{
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var store=new PasswordStore(context);store.save("synthetic-instrumentation-password");
        assertEquals("synthetic-instrumentation-password",store.load());
        assertFalse(context.getSharedPreferences("receiver_security",0).getString("encryptedPassword","").contains("synthetic-instrumentation-password"));
        byte[] one=PairingIdentityStore.load(context),two=PairingIdentityStore.load(context);assertEquals(32,one.length);assertArrayEquals(one,two);
    }
    @Test public void protocolServerRunsOnAndroid()throws Exception{
        Playback sink=new Playback(){public void onVideoFormat(VideoStreamInfo i){}public void onVideo(byte[] b){}public void onVideoSrcDisconnect(){}public void onAudioFormat(AudioStreamInfo i){}public void onAudio(byte[] b){}public void onAudioSrcDisconnect(){}};
        try(EngineReceiver receiver=new EngineReceiver("Android Test","02:11:22:33:44:55","synthetic-password",new byte[32],sink)){
            receiver.startWithoutDiscoveryForTest();
            try(Socket socket=new Socket("127.0.0.1",receiver.port())){
                socket.setSoTimeout(10000);socket.getOutputStream().write("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                byte[] response=new byte[4096];int n=socket.getInputStream().read(response);assertTrue(n>0);assertTrue(new String(response,0,n,StandardCharsets.US_ASCII).contains("200 OK"));
            }
        }
    }
    @Test public void pairingKeyExchangeRunsOnAndroidWithoutTheOldCryptoDependency()throws Exception{
        Playback sink=new Playback(){public void onVideoFormat(VideoStreamInfo i){}public void onVideo(byte[] b){}public void onVideoSrcDisconnect(){}public void onAudioFormat(AudioStreamInfo i){}public void onAudio(byte[] b){}public void onAudioSrcDisconnect(){}};
        try(EngineReceiver receiver=new EngineReceiver("Android Pairing Test","02:11:22:33:44:55","synthetic-password",new byte[32],sink)){
            receiver.startWithoutDiscoveryForTest();
            try(Socket socket=new Socket("127.0.0.1",receiver.port())){
                socket.setSoTimeout(15000);byte[] request=new byte[68];request[0]=1;
                String publicKey="8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
                for(int i=0;i<32;i++)request[i+4]=(byte)Integer.parseInt(publicKey.substring(i*2,i*2+2),16);
                Arrays.fill(request,36,68,(byte)1);
                socket.getOutputStream().write("POST /pair-verify RTSP/1.0\r\nCSeq: 1\r\nActive-Remote: android-fixture\r\nContent-Type: application/octet-stream\r\nContent-Length: 68\r\n\r\n".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(request);
                ByteArrayOutputStream headers=new ByteArrayOutputStream();InputStream input=socket.getInputStream();
                while(!headers.toString("US-ASCII").endsWith("\r\n\r\n")){int b=input.read();assertTrue(b>=0);headers.write(b);assertTrue(headers.size()<8192);}
                assertTrue(headers.toString("US-ASCII").contains("200 OK"));
                java.util.regex.Matcher length=java.util.regex.Pattern.compile("(?i)Content-Length: (\\d+)").matcher(headers.toString("US-ASCII"));assertTrue(length.find());assertEquals(96,Integer.parseInt(length.group(1)));
                byte[] response=new byte[96];int at=0;while(at<96){int n=input.read(response,at,96-at);assertTrue(n>0);at+=n;}
                assertFalse(Arrays.equals(new byte[32],Arrays.copyOf(response,32)));
            }
        }
    }
    @Test public void h264MirrorDecodesARealFrameToAndroidSurface()throws Exception{
        CountDownLatch picture=new CountDownLatch(1);List<String> messages=new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Float> aspect=new java.util.concurrent.atomic.AtomicReference<>(0f);
        MirrorVideoPlayer player=new MirrorVideoPlayer(s -> {messages.add(s);if(s.equals("Mirroring picture received"))picture.countDown();},aspect::set);
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        CodecTestActivity activity=(CodecTestActivity)instrumentation.startActivitySync(new android.content.Intent(context,CodecTestActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        android.view.SurfaceView[] target=new android.view.SurfaceView[1];CountDownLatch ready=new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            target[0]=activity.output;
            target[0].getHolder().addCallback(new android.view.SurfaceHolder.Callback(){
                public void surfaceCreated(android.view.SurfaceHolder h){player.surface(h.getSurface());ready.countDown();}
                public void surfaceChanged(android.view.SurfaceHolder h,int f,int w,int hgt){player.surface(h.getSurface());ready.countDown();}
                public void surfaceDestroyed(android.view.SurfaceHolder h){}
            });
            if(target[0].getHolder().getSurface().isValid())ready.countDown();
        });
        try{
            assertTrue("Android activity surface was not created",ready.await(15,TimeUnit.SECONDS));
            player.surface(target[0].getHolder().getSurface());player.begin();
            byte[] stream=asset("mirror-stream.h264");int previous=0;
            for(int at=0;at+5<stream.length;at++){
                int prefix=stream[at]==0&&stream[at+1]==0&&stream[at+2]==1?3:stream[at]==0&&stream[at+1]==0&&stream[at+2]==0&&stream[at+3]==1?4:0;
                if(prefix>0&&(stream[at+prefix]&31)==9&&at>previous){player.enqueue(Arrays.copyOfRange(stream,previous,at));previous=at;Thread.sleep(33);}
                if(prefix>0)at+=prefix;
            }
            player.enqueue(Arrays.copyOfRange(stream,previous,stream.length));
            boolean decoded=picture.await(25,TimeUnit.SECONDS);assertTrue(messages+"; decoder="+player.diagnostics(),decoded);
            assertEquals(320f/180,aspect.get(),0.0001f);
        }finally{player.destroy();instrumentation.runOnMainSync(activity::finish);}

    }
    @Test public void aacStreamDecodesAndWritesRealPcmToAudioTrack()throws Exception{
        CountDownLatch sound=new CountDownLatch(1);List<String> messages=new CopyOnWriteArrayList<>();
        AirPlayAudioPlayer player=new AirPlayAudioPlayer(s -> {messages.add(s);if(s.equals("Audio playback started"))sound.countDown();});
        try{
            player.format(new AudioStreamInfo.AudioStreamInfoBuilder().compressionType(AudioStreamInfo.CompressionType.AAC).audioFormat(AudioStreamInfo.AudioFormat.AAC_LC_44100_2).samplesPerFrame(1024).build());
            byte[] adts=asset("audio-fixture.aac");int at=0;
            while(at+7<=adts.length){int length=((adts[at+3]&3)<<11)|((adts[at+4]&255)<<3)|((adts[at+5]&0xe0)>>5);int header=(adts[at+1]&1)!=0?7:9;assertTrue(length>=header&&at+length<=adts.length);player.enqueue(adts,at+header,length-header);at+=length;}
            assertTrue(messages.toString(),sound.await(25,TimeUnit.SECONDS));
        }finally{player.destroy();}
    }
    @Test public void shippedAlacNativeLibraryDecodesOnAndroid(){
        long decoder=NativeAlac.create(44100,352);assertNotEquals(0,decoder);
        try{
            StringBuilder bits=new StringBuilder("001"+"0000000000000000"+"0"+"00"+"1");
            for(int i=0;i<352;i++)bits.append("0000010011010010").append("1111101100101110");bits.append("111");while(bits.length()%8!=0)bits.append('0');
            byte[] encoded=new byte[bits.length()/8];for(int i=0;i<encoded.length;i++)encoded[i]=(byte)Integer.parseInt(bits.substring(i*8,i*8+8),2);
            byte[] pcm=NativeAlac.decode(decoder,encoded);assertNotNull(pcm);assertEquals(352*4,pcm.length);assertEquals((byte)0xd2,pcm[0]);assertEquals((byte)0x04,pcm[1]);assertEquals((byte)0x2e,pcm[2]);assertEquals((byte)0xfb,pcm[3]);
        }finally{NativeAlac.destroy(decoder);}
    }
}
