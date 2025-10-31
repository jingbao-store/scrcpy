package com.genymobile.scrcpy.video;

public enum VideoSource {
    DISPLAY("display"),
    CAMERA("camera"),
    COMPOSITE("composite");

    private final String name;

    VideoSource(String name) {
        this.name = name;
    }

    public static VideoSource findByName(String name) {
        for (VideoSource videoSource : VideoSource.values()) {
            if (name.equals(videoSource.name)) {
                return videoSource;
            }
        }

        return null;
    }
}
