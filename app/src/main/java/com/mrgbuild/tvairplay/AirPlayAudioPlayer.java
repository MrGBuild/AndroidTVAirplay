package com.mrgbuild.tvairplay;

import android.media.*;
import android.os.*;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** Native ALAC / platform AAC decoding, isolated from network and video threads. */
final class AirPlayAudioPlayer {
    private final HandlerThread thread=new HandlerThread("airplay-audio");
    private final Handler handler;
    private final ArrayBlockingQueue<byte[]> packets=new ArrayBlockingQueue<>(80);
    private final Consumer<String> status;
    private final MediaCodec.BufferInfo outputInfo=new MediaCodec.BufferInfo();
    private MediaCodec codec;private AudioTrack track;private long alac;
    private volatile boolean active;
    private volatile float volume=1;
    private long samples;
    private boolean heard;
    private int rate=44100,spf=480;
    AirPlayAudioPlayer(Consumer<String> status){this.status=status;thread.start();handler=new Handler(thread.getLooper());}
    void format(AudioStreamInfo info){
        active=false;packets.clear();
        handler.post(() -> {
            release();samples=0;heard=false;handler.removeCallbacks(tick);
            try {
                String format=info.getAudioFormat()==null?"":info.getAudioFormat().name();
                if(format.contains("24_"))throw new IllegalArgumentException("24-bit audio is not advertised");
                rate=format.contains("48000")?48000:44100;
                spf=info.getSamplesPerFrame()>0?info.getSamplesPerFrame():480;
                if(info.getCompressionType()==AudioStreamInfo.CompressionType.ALAC){
                    if(info.getSamplesPerFrame()<=0)spf=352;
                    alac=NativeAlac.create(rate,spf);if(alac==0)throw new IllegalArgumentException("Unsupported ALAC format");
                }else if(info.getCompressionType()==AudioStreamInfo.CompressionType.AAC || info.getCompressionType()==AudioStreamInfo.CompressionType.AAC_ELD){
                    boolean eld=info.getCompressionType()==AudioStreamInfo.CompressionType.AAC_ELD;
                    MediaFormat f=MediaFormat.createAudioFormat("audio/mp4a-latm",rate,2);
                    f.setInteger(MediaFormat.KEY_AAC_PROFILE,eld?MediaCodecInfo.CodecProfileLevel.AACObjectELD:MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    f.setByteBuffer("csd-0",ByteBuffer.wrap(audioSpecificConfig(eld,rate,spf)));
                    f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384);
                    codec=MediaCodec.createDecoderByType("audio/mp4a-latm");codec.configure(f,null,null,0);codec.start();
                }else throw new IllegalArgumentException("Unsupported audio codec");
                track(rate,2);active=true;status.accept("Audio stream connected");handler.post(tick);
            }catch(Exception|LinkageError e){release();status.accept("Audio decoder could not start ("+e.getClass().getSimpleName()+")");}
        });
    }
    static byte[] audioSpecificConfig(boolean eld,int rate,int spf){
        int frequency=rate==48000?3:4;
        if(!eld){int value=(2<<11)|(frequency<<7)|(2<<3);return new byte[]{(byte)(value>>8),(byte)value};}
        int value=(31<<27)|(7<<21)|(frequency<<17)|(2<<13)|((spf==480?1:0)<<12);
        return new byte[]{(byte)(value>>24),(byte)(value>>16),(byte)(value>>8),(byte)value};
    }
    void enqueue(byte[] bytes,int offset,int length){
        if(bytes==null||length<1||length>16384||offset<0||offset+length>bytes.length)return;
        byte[] copy=Arrays.copyOfRange(bytes,offset,offset+length);
        if(!packets.offer(copy)){packets.poll();packets.offer(copy);}
    }
    void volume(double value){volume=(float)Math.max(0,Math.min(1,value));handler.post(() -> {if(track!=null)track.setVolume(volume);});}
    private void track(int rate,int channels){
        if(track!=null){track.stop();track.release();}
        int mask=channels==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
        int min=AudioTrack.getMinBufferSize(rate,mask,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IllegalArgumentException("Unsupported audio output");
        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min,rate/5*channels*2)).build();
        track.setVolume(volume);track.play();
    }
    private void write(byte[] pcm){int offset=0;while(active&&track!=null&&offset<pcm.length){int n=track.write(pcm,offset,pcm.length-offset);if(n<=0)break;offset+=n;if(!heard){heard=true;status.accept("Audio playback started");}}}
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        try{
            for(int n=0;n<8;n++){
                byte[] data=packets.peek();if(data==null)break;
                if(alac!=0){packets.poll();byte[] pcm=NativeAlac.decode(alac,data);if(pcm!=null)write(pcm);continue;}
                int index=codec.dequeueInputBuffer(0);if(index<0)break;
                ByteBuffer input=codec.getInputBuffer(index);if(input==null||input.capacity()<data.length)throw new IllegalArgumentException("Audio packet exceeds decoder capacity");
                packets.poll();input.clear();input.put(data);codec.queueInputBuffer(index,0,data.length,samples*1000000L/rate,0);samples+=spf;
            }
            if(codec!=null)for(int n=0;n<12;n++){
                int index=codec.dequeueOutputBuffer(outputInfo,0);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();track(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT));continue;}
                if(index<0)break;
                ByteBuffer output=codec.getOutputBuffer(index);
                if(output!=null&&outputInfo.size>0){output.position(outputInfo.offset);output.limit(outputInfo.offset+outputInfo.size);byte[] pcm=new byte[outputInfo.size];output.get(pcm);write(pcm);}
                codec.releaseOutputBuffer(index,false);
            }
        }catch(Exception e){active=false;release();status.accept("Audio playback stopped ("+e.getClass().getSimpleName()+")");return;}
        handler.postDelayed(this,5);
    }};
    void stop(){active=false;packets.clear();handler.post(() -> {handler.removeCallbacks(tick);release();});}
    void destroy(){stop();handler.post(() -> thread.quitSafely());}
    private void release(){
        if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();codec=null;}
        if(alac!=0){NativeAlac.destroy(alac);alac=0;}
        if(track!=null){try{track.stop();}catch(Exception ignored){}track.release();track=null;}
    }
}
