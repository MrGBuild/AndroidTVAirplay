package com.mrgbuild.tvairplay;

import android.media.*;
import android.os.*;
import android.view.Surface;
import androidx.media3.container.NalUnitUtil;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** H.264 mirror decoder. All codec work stays on a dedicated thread. */
@androidx.media3.common.util.UnstableApi
final class MirrorVideoPlayer {
    private final HandlerThread thread = new HandlerThread("mirror-video");
    private final Handler handler;
    private final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(40);
    private final Consumer<String> status;
    private final Consumer<Float> pictureAspect;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private Surface surface;
    private byte[] sps, pps;
    private volatile boolean active;
    private volatile byte[] configPacket;
    private boolean shown;
    private volatile String phase="Idle";
    private volatile String pictureSize="";
    String diagnostics(){return phase+(pictureSize.isEmpty()?"":" • decoded "+pictureSize);}
    MirrorVideoPlayer(Consumer<String> status) { this(status,ratio -> {}); }
    MirrorVideoPlayer(Consumer<String> status,Consumer<Float> pictureAspect) { this.status=status;this.pictureAspect=pictureAspect;thread.start();handler=new Handler(thread.getLooper()); }
    void begin() { frames.clear();configPacket=null;pictureSize="";active=true;status.accept("Starting screen mirroring"); handler.post(() -> { release();sps=null;pps=null;shown=false;handler.removeCallbacks(tick);handler.post(tick); }); }
    void surface(Surface value) { handler.post(() -> { if(surface!=value){release();surface=value;} }); }
    void enqueue(byte[] bytes) {
        if (!active || bytes==null || bytes.length>4*1024*1024) return;
        if(bytes.length>4 && (bytes[4]&31)==7)configPacket=bytes;
        if (!frames.offer(bytes)) {frames.poll();frames.offer(bytes);}
    }
    private static int prefix(byte[] data,int at){
        if(at+3>data.length||data[at]!=0||data[at+1]!=0)return 0;
        if(data[at+2]==1)return 3;
        return at+4<=data.length&&data[at+2]==0&&data[at+3]==1?4:0;
    }
    private void configuration(byte[] data) {
        for(int at=0;at+3<data.length;at++) {
            int start=prefix(data,at);if(start==0||at+start>=data.length)continue;
            int nal=at+start,end=nal+1;while(end<data.length&&prefix(data,end)==0)end++;
            int type=data[nal]&31;
            if(type==7||type==8){
                byte[] next=new byte[end-nal+4];next[3]=1;System.arraycopy(data,nal,next,4,end-nal);
                if(type==7&&!Arrays.equals(sps,next)){release();sps=next;}
                if(type==8&&!Arrays.equals(pps,next)){release();pps=next;}
            }
            at=end-1;
        }
    }
    private byte[] videoPayload(byte[] data){
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();boolean picture=false;
        for(int at=0;at+3<data.length;at++){
            int start=prefix(data,at);if(start==0||at+start>=data.length)continue;
            int nal=at+start,end=nal+1;while(end<data.length&&prefix(data,end)==0)end++;
            int type=data[nal]&31;
            if(type!=7&&type!=8&&type!=9){out.write(0);out.write(0);out.write(0);out.write(1);out.write(data,nal,end-nal);}
            picture|=type==1||type==5;at=end-1;
        }
        return picture?out.toByteArray():new byte[0];
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(!active)return;
        try {
            if(surface==null || !surface.isValid()){phase="Waiting for a valid picture surface";handler.postDelayed(this,20);return;}
            phase=(shown?"Displaying mirror pictures":"Waiting for mirror configuration")+" • queued="+frames.size();
            if(configPacket!=null)configuration(configPacket);
            for(int count=0;count<5;count++) {
                byte[] data=frames.peek();if(data==null)break;configuration(data);
                if(codec==null) {
                    if(sps==null||pps==null){phase="Waiting for H.264 parameters • SPS="+(sps!=null)+" PPS="+(pps!=null);frames.poll();continue;}
                    var parsed=NalUnitUtil.parseSpsNalUnit(sps,4,sps.length);
                    if(parsed.width<1||parsed.height<1||parsed.width>4096||parsed.height>4096)throw new IllegalArgumentException("Unsupported mirror dimensions");
                    pictureAspect.accept(parsed.width*parsed.pixelWidthHeightRatio/parsed.height);
                    pictureSize=parsed.width+"×"+parsed.height;
                    MediaFormat format=MediaFormat.createVideoFormat("video/avc",parsed.width,parsed.height);
                    format.setByteBuffer("csd-0",ByteBuffer.wrap(sps));format.setByteBuffer("csd-1",ByteBuffer.wrap(pps));
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,4*1024*1024);
                    format.setInteger("low-latency",1);
                    status.accept("Preparing picture");
                    phase="Configuring platform H.264 decoder";
                    codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,surface,null,0);codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);codec.start();
                    status.accept("Video ready • "+parsed.width+"×"+parsed.height);
                }
                byte[] payload=videoPayload(data);if(payload.length==0){frames.poll();continue;}
                int index=codec.dequeueInputBuffer(0);if(index<0)break;
                phase="Queuing a mirror picture";
                ByteBuffer input=codec.getInputBuffer(index);if(input==null||input.capacity()<payload.length)throw new IllegalArgumentException("Mirror frame exceeds decoder capacity");
                frames.poll();input.clear();input.put(payload);
                codec.queueInputBuffer(index,0,payload.length,System.nanoTime()/1000,0);
            }
            if(codec!=null)for(int n=0;n<8;n++) {
                int index=codec.dequeueOutputBuffer(info,0);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat output=codec.getOutputFormat();
                    int width=output.getInteger(MediaFormat.KEY_WIDTH),height=output.getInteger(MediaFormat.KEY_HEIGHT);
                    if(output.containsKey("crop-left")&&output.containsKey("crop-right"))width=output.getInteger("crop-right")-output.getInteger("crop-left")+1;
                    if(output.containsKey("crop-top")&&output.containsKey("crop-bottom"))height=output.getInteger("crop-bottom")-output.getInteger("crop-top")+1;
                    float pixelRatio=1f;
                    if(sps!=null)pixelRatio=NalUnitUtil.parseSpsNalUnit(sps,4,sps.length).pixelWidthHeightRatio;
                    if(width>0&&height>0)pictureAspect.accept(width*pixelRatio/height);
                    continue;
                }
                if(index<0)break;
                codec.releaseOutputBuffer(index,true);
                if(!shown){shown=true;phase="Picture decoded";status.accept("Mirroring picture received");}
            }
        }catch(Exception e){active=false;release();frames.clear();status.accept("Mirror decoder could not play this format ("+e.getClass().getSimpleName()+")");return;}
        handler.postDelayed(this,5);
    }};
    void stop(){active=false;frames.clear();handler.post(() -> {handler.removeCallbacks(tick);release();});}
    void destroy(){stop();handler.post(() -> thread.quitSafely());}
    private void release(){if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();codec=null;}}
}
