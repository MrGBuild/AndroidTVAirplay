package com.github.serezhka.airplay.server.internal.handler.audio;
import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.server.Playback;
import com.github.serezhka.airplay.server.internal.packet.AudioPacket;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
/** Android adaptation: bounded-latency receive with wrap-safe RTP sequence checks. */
public class AudioHandler extends ChannelInboundHandlerAdapter {
    private final FairPlaySession airPlay;
    private final Playback playback;
    private int previous = -1;
    public AudioHandler(FairPlaySession airPlay, Playback playback) { this.airPlay=airPlay; this.playback=playback; }
    public static boolean newer(int current, int previous) {
        int distance = (current - previous) & 0xffff;
        return previous < 0 || (distance > 0 && distance < 0x8000);
    }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        AudioPacket packet = (AudioPacket) msg;
        if (!newer(packet.getSequenceNumber(), previous)) return;
        previous = packet.getSequenceNumber();
        int length = packet.getEncodedAudioSize();
        airPlay.decryptAudio(packet.getEncodedAudio(), length);
        playback.onAudio(packet.getEncodedAudio(), 0, length);
    }
}
