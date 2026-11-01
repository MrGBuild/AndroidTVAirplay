package com.mrgbuild.engine;

/** Requested stream limits; the sender chooses the actual encoded stream. */
public enum MirrorQuality {
    COMPATIBLE(1920,1080,30,"Compatible — 1080p / 30 fps"),
    SMOOTH(1920,1080,60,"Smooth — 1080p / 60 fps"),
    DETAIL(3840,2160,30,"High detail — up to 4K / 30 fps");
    public final int width,height,fps;
    public final String label;
    MirrorQuality(int width,int height,int fps,String label){this.width=width;this.height=height;this.fps=fps;this.label=label;}
}
