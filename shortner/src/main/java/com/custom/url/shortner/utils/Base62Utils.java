package com.custom.url.shortner.utils;


public final class Base62Utils {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;

    // The maximum theoretical valid Base62 string string representation of a positive Long.MAX_VALUE
    private static final String MAX_LONG_BASE62 = "aZlkox55xAg";

    private Base62Utils() {
        throw new UnsupportedOperationException("This utility class should not be instantiated");
    }

    /**
     * Encodes a positive 64-bit Snowflake ID into a compact Base62 URL path.
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Value to encode must be non-negative");
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0)); // Returns "0"
        }

        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(ALPHABET.charAt((int) (value % BASE)));
            value /= BASE;
        }
        return sb.reverse().toString();
    }

    /**
     * Decodes a compact Base62 URL token back into its structural 64-bit Snowflake ID.
     */
    public static long decode(String base62Str) {
        if (base62Str == null || base62Str.isBlank()) {
            throw new IllegalArgumentException("Base62 string cannot be null or empty");
        }

        // Catch arithmetic overflows before they hit system processing memory bounds
        if (base62Str.length() > MAX_LONG_BASE62.length()) {
            throw new IllegalArgumentException("Base62 string string is too long; exceeds maximum Long value limits.");
        }

        long result = 0;

        for (int i = 0; i < base62Str.length(); i++) {
            char c = base62Str.charAt(i);
            int value = ALPHABET.indexOf(c);

            if (value == -1) {
                throw new IllegalArgumentException("Invalid character in Base62 string: " + c);
            }

            // Guard check for overflow before performing the multiplication step
            if (result > (Long.MAX_VALUE - value) / BASE) {
                throw new IllegalArgumentException("Base62 string decoding caused an arithmetic numerical overflow.");
            }

            result = (result * BASE) + value;
        }

        return result;
    }
}
