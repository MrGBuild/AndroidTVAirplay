package com.mrgbuild.receiver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Legacy AirPlay Digest MD5, with a fresh unpredictable nonce per TCP connection. */
final class DigestAuth {
    private static final String REALM = "airplay";
    private static final Pattern FIELD = Pattern.compile("\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"\\r\\n]*)\"|([^,\\s]+))\\s*(?:,|$)");
    private final String password;
    private final String nonce;

    DigestAuth(String password) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("A receiver password is required");
        this.password = password;
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        nonce = hex(random);
    }
    String challenge() { return "Digest realm=\"" + REALM + "\", nonce=\"" + nonce + "\", algorithm=MD5"; }

    boolean accepts(String authorization, String method, String target) {
        if (authorization == null || !authorization.startsWith("Digest ")) return false;
        String fields = authorization.substring(7);
        Map<String,String> values = new HashMap<>();
        Matcher matcher = FIELD.matcher(fields);
        int offset = 0;
        while (offset < fields.length()) {
            matcher.region(offset, fields.length());
            if (!matcher.lookingAt()) return false;
            String value = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
            if (values.put(matcher.group(1), value) != null) return false;
            offset = matcher.end();
        }
        String username = values.get("username"), response = values.get("response");
        if (username == null || response == null || !response.matches("[0-9a-fA-F]{32}")
                || !REALM.equals(values.get("realm")) || !nonce.equals(values.get("nonce"))
                || !target.equals(values.get("uri")) || values.containsKey("qop")
                || !"MD5".equalsIgnoreCase(values.getOrDefault("algorithm", "MD5"))) return false;
        String ha1 = md5(username + ":" + REALM + ":" + password);
        String ha2 = md5(method + ":" + target);
        byte[] expected = md5(ha1 + ":" + nonce + ":" + ha2).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, response.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
    private static String md5(String value) {
        try { return hex(MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Legacy digest unavailable", e); }
    }
    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        String alphabet = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet.charAt((bytes[i] & 255) >>> 4);
            result[i * 2 + 1] = alphabet.charAt(bytes[i] & 15);
        }
        return new String(result);
    }
}
