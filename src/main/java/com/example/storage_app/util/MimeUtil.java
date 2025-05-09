package com.example.storage_app.util;

import org.apache.tika.Tika;
import org.apache.tika.io.LookaheadInputStream;

import java.io.IOException;
import java.io.InputStream;

public class MimeUtil {
    private static final Tika tika = new Tika();
    private static final int LOOKAHEAD = 64 * 1024; // 64 KB

    /**
     * Wraps the raw InputStream in a LookaheadInputStream, uses Tika to
     * detect the MIME type, then resets the LookaheadInputStream back to 0.
     * Returns the LookaheadInputStream (positioned at start) along with the detected type.
     */
    public static Detected detect(InputStream raw) throws IOException {
        LookaheadInputStream lookahead = new LookaheadInputStream(raw, LOOKAHEAD);
        // detect content type (reads up to LOOKAHEAD bytes, then we reset)
        String type = tika.detect(lookahead);
        lookahead.reset();
        return new Detected(lookahead, type);
    }

    public static class Detected {
        public final LookaheadInputStream stream;  // mark/reset-safe
        public final String contentType;
        public Detected(LookaheadInputStream s, String ct) {
            this.stream = s;
            this.contentType = ct;
        }
    }
}
