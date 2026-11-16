package io.github.cnrozkn.s3client.storage;

/**
 * Where the next listing page starts. It travels in the URL, so it is parsed defensively; a bad token only means
 * starting from the first page, and the S3 prefix still bounds what can be listed.
 *
 * @param filesOnly every folder was already shown on the first page, so later pages skip folders
 * @param after key position to continue after
 * @param shownUntil folders ending at or before this position were already shown; null when there are none
 */
record PageToken(boolean filesOnly, String after, String shownUntil) {

    static PageToken filesOnly(String after) {
        return new PageToken(true, after, null);
    }

    static PageToken inPlace(String after, String shownUntil) {
        boolean ahead = shownUntil != null && KeyScan.compareKeys(after, shownUntil) < 0;
        return new PageToken(false, after, ahead ? shownUntil : null);
    }

    PageToken resumeAt(String position) {
        return filesOnly ? filesOnly(position) : inPlace(position, shownUntil);
    }

    boolean skipsFolder(String folderPrefix) {
        return filesOnly
                || shownUntil != null && KeyScan.compareKeys(folderPrefix + KeyScan.AFTER_ALL, shownUntil) <= 0;
    }

    /** {@code F<after>} or {@code A<length of after>:<after><shownUntil>}. */
    String encode() {
        if (filesOnly) {
            return "F" + after;
        }
        return "A" + after.length() + ":" + after + (shownUntil == null ? "" : shownUntil);
    }

    static PageToken parse(String token) {
        if (token == null || token.length() < 2) {
            return null;
        }
        String body = token.substring(1);
        if (token.charAt(0) == 'F') {
            return filesOnly(body);
        }
        if (token.charAt(0) != 'A') {
            return null;
        }
        int colon = body.indexOf(':');
        if (colon < 1 || colon > 4) {
            return null;
        }
        int length;
        try {
            length = Integer.parseInt(body.substring(0, colon));
        } catch (NumberFormatException e) {
            return null;
        }
        int start = colon + 1;
        if (length < 1 || start + length > body.length()) {
            return null;
        }
        String rest = body.substring(start + length);
        return inPlace(body.substring(start, start + length), rest.isEmpty() ? null : rest);
    }
}
