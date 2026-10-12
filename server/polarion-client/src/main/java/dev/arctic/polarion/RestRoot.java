package dev.arctic.polarion;

import java.util.Locale;

/**
 * Turns a user-supplied Polarion URL into the REST root, for example {@code https://host/polarion/rest/v1}.
 *
 * <p>URL splitting follows Python's {@code urllib.parse.urlparse} so results match the prototype exactly; it is more
 * lenient than {@link java.net.URI}.
 */
public final class RestRoot {

    static final String INVALID = "The Polarion URL must be an http(s) URL of the Polarion server.";

    private RestRoot() {
    }

    /** @throws MissingCredentialsException if the URL is not an http(s) URL with a host */
    public static String normalize(String url) {
        String rest = stripLeadingControlOrSpace(pythonStrip(url));
        rest = rest.replace("\t", "").replace("\r", "").replace("\n", "");

        String scheme = "";
        int colon = rest.indexOf(':');
        if (colon > 0 && isAsciiLetter(rest.charAt(0)) && rest.substring(0, colon).chars().allMatch(RestRoot::isSchemeChar)) {
            scheme = rest.substring(0, colon).toLowerCase(Locale.ROOT);
            rest = rest.substring(colon + 1);
        }
        String netloc = "";
        if (rest.startsWith("//")) {
            int end = rest.length();
            for (char delimiter : new char[] {'/', '?', '#'}) {
                int at = rest.indexOf(delimiter, 2);
                if (at >= 0) {
                    end = Math.min(end, at);
                }
            }
            netloc = rest.substring(2, end);
            rest = rest.substring(end);
        }
        if (!scheme.equals("http") && !scheme.equals("https") || netloc.isEmpty() || unbalancedBrackets(netloc)) {
            throw new MissingCredentialsException(INVALID);
        }
        String path = withoutParams(before(before(rest, '#'), '?'));

        path = stripTrailingSlashes(path);
        String restPath;
        if (path.endsWith("/polarion/rest/v1") || path.endsWith("/rest/v1")) {
            restPath = path;
        } else if (path.endsWith("/polarion")) {
            restPath = path + "/rest/v1";
        } else {
            restPath = path + "/polarion/rest/v1";
        }
        return scheme + "://" + netloc + restPath;
    }

    private static String before(String value, char delimiter) {
        int at = value.indexOf(delimiter);
        return at < 0 ? value : value.substring(0, at);
    }

    /** {@code ;params} on the last path segment, as urlparse splits them off for http(s). */
    private static String withoutParams(String path) {
        int at = path.indexOf(';', Math.max(path.lastIndexOf('/'), 0));
        return at < 0 ? path : path.substring(0, at);
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    private static boolean unbalancedBrackets(String netloc) {
        return netloc.contains("[") != netloc.contains("]");
    }

    private static boolean isAsciiLetter(char c) {
        return c < 128 && Character.isLetter(c);
    }

    private static boolean isSchemeChar(int c) {
        return c < 128 && Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.';
    }

    private static String stripLeadingControlOrSpace(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) <= ' ') {
            start++;
        }
        return value.substring(start);
    }

    /** Python {@code str.strip()}: also strips no-break and other Unicode spaces that {@link String#strip} keeps. */
    private static String pythonStrip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPythonSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isPythonSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPythonSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }
}
