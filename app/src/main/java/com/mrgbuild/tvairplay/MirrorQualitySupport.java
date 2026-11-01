package com.mrgbuild.tvairplay;

import android.media.*;
import android.os.Build;
import com.mrgbuild.engine.MirrorQuality;
import java.util.*;

/** Offer higher limits only when a hardware AVC decoder reports support. */
final class MirrorQualitySupport {
    static List<MirrorQuality> supported(){
        List<MirrorQuality> result=new ArrayList<>();result.add(MirrorQuality.COMPATIBLE);
        try{
            MediaCodecInfo[] codecs=new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for(MirrorQuality quality:new MirrorQuality[]{MirrorQuality.SMOOTH,MirrorQuality.DETAIL}){
                for(MediaCodecInfo codec:codecs){
                    if(codec.isEncoder())continue;
                    String name=codec.getName().toLowerCase(Locale.ROOT);
                    if(Build.VERSION.SDK_INT>=29?!codec.isHardwareAccelerated():name.startsWith("omx.google.")||name.startsWith("c2.android."))continue;
                    try{
                        var caps=codec.getCapabilitiesForType("video/avc").getVideoCapabilities();
                        if(caps!=null&&caps.areSizeAndRateSupported(quality.width,quality.height,quality.fps)
                                &&caps.areSizeAndRateSupported(quality.height,quality.width,quality.fps)) {result.add(quality);break;}
                    }catch(IllegalArgumentException ignored){}
                }
            }
        }catch(RuntimeException ignored){} // Keep the existing 1080p30 fallback.
        return result;
    }
}
