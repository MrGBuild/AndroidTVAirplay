package com.mrgbuild.receiver;

import java.util.regex.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DigestAuthTest {
    private String nonce(DigestAuth auth) {
        Matcher m = Pattern.compile("nonce=\"([^\"]+)\"").matcher(auth.challenge()); assertTrue(m.find()); return m.group(1);
    }
    @Test public void nonceIsConnectionSpecificAndPasswordIsNotExposed() throws Exception {
        DigestAuth a = new DigestAuth("correct-password"), b = new DigestAuth("correct-password");
        assertNotEquals(nonce(a), nonce(b)); assertFalse(a.challenge().contains("correct-password"));
        String response = AirPlayServerTest.authorization("POST", "/stop", nonce(a), "correct-password");
        assertTrue(a.accepts(response, "POST", "/stop")); assertFalse(b.accepts(response, "POST", "/stop"));
    }
    @Test public void credentialsAreBoundToMethodAndExactTarget() throws Exception {
        DigestAuth auth = new DigestAuth("correct-password");
        String response = AirPlayServerTest.authorization("POST", "/scrub?position=1", nonce(auth), "correct-password");
        assertTrue(auth.accepts(response, "POST", "/scrub?position=1"));
        assertFalse(auth.accepts(response, "GET", "/scrub?position=1"));
        assertFalse(auth.accepts(response, "POST", "/scrub?position=2"));
    }
    @Test public void passwordChangeRejectsPreviousCredentials() throws Exception {
        DigestAuth auth = new DigestAuth("new-password");
        assertFalse(auth.accepts(AirPlayServerTest.authorization("POST", "/stop", nonce(auth), "old-password"), "POST", "/stop"));
        assertTrue(auth.accepts(AirPlayServerTest.authorization("POST", "/stop", nonce(auth), "new-password"), "POST", "/stop"));
    }
    @Test public void malformedOrAmbiguousCredentialsAreRejected() throws Exception {
        DigestAuth auth = new DigestAuth("correct-password");
        String valid = AirPlayServerTest.authorization("POST", "/stop", nonce(auth), "correct-password");
        for (String value : new String[]{null, "", "Basic cGFzc3dvcmQ=", "Digest ", "Digest ,", valid + ",broken", valid + ", username=\"duplicate\"", valid.replace("airplay", "wrong"), valid + ", algorithm=SHA-256", valid + ", qop=auth"}) {
            assertFalse(auth.accepts(value, "POST", "/stop"));
        }
    }
}
