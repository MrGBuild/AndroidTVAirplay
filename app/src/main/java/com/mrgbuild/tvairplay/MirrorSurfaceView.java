package com.mrgbuild.tvairplay;

import android.content.Context;
import android.view.SurfaceView;

/** Fit the decoded picture inside the TV without cropping or stretching. UI thread only. */
final class MirrorSurfaceView extends SurfaceView {
    private float aspectRatio;
    MirrorSurfaceView(Context context){super(context);}
    void setVideoAspectRatio(float ratio){
        if(!Float.isFinite(ratio)||ratio<=0||Math.abs(aspectRatio-ratio)<0.0001f)return;
        aspectRatio=ratio;requestLayout();
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=getDefaultSize(getSuggestedMinimumWidth(),widthSpec);
        int height=getDefaultSize(getSuggestedMinimumHeight(),heightSpec);
        if(aspectRatio>0&&width>0&&height>0){
            if(width/(float)height>aspectRatio)width=Math.max(1,Math.round(height*aspectRatio));
            else height=Math.max(1,Math.round(width/aspectRatio));
        }
        setMeasuredDimension(width,height);
    }
}
