package com.mrgbuild.tvairplay;
/** Non-exported debug-only surface host for real MediaCodec instrumentation. */
public final class CodecTestActivity extends android.app.Activity {
    public android.view.SurfaceView output;
    @Override public void onCreate(android.os.Bundle saved){
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        output=new android.view.SurfaceView(this);setContentView(output);
    }
}
