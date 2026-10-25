package com.mrgbuild.receiver;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Leaves room for the required RAOP identifier and audio suffix in a DNS label. */
public final class ReceiverName {
    public static final String DEFAULT = "TV AirPlay";
    private ReceiverName() { }
    public static String validate(String value) {
        if (value == null) throw new IllegalArgumentException("Enter a receiver name");
        String name = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (name.isEmpty()) throw new IllegalArgumentException("Enter a receiver name");
        for (int offset = 0; offset < name.length();) {
            int cp = name.codePointAt(offset);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || Character.getType(cp) == Character.SURROGATE) {
                throw new IllegalArgumentException("Use letters, numbers, spaces or ordinary symbols");
            }
            offset += Character.charCount(cp);
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > 40) throw new IllegalArgumentException("Use a shorter receiver name");
        return name;
    }
    public static String audioServiceName(String deviceId, String name) {
        if (deviceId == null || !deviceId.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) throw new IllegalArgumentException("Invalid receiver ID");
        return deviceId.replace(":", "") + "@" + validate(name) + " Audio";
    }
}
