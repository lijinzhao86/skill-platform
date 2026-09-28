package com.skillmasterai.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opaque cursors for keyset pagination.
 *
 * <p>§4.1 forbids offset pagination, and the reason is worth keeping in view: an offset is a
 * position in a result set that is being written to. A row inserted ahead of the window pushes
 * everything back, so page 2 repeats a row the client already saw; a row deleted pulls it
 * forward, so a row is skipped. A keyset cursor names the <em>last row returned</em> and asks for
 * the ones after it, which stays correct however the table changes in between.
 *
 * <p><strong>{@link #ordering} is in the payload deliberately.</strong> A cursor is only
 * meaningful against the ordering that produced it — the same key means something else under a
 * different sort, or under the same sort with different weights. Carrying the ordering's identity
 * lets the reader reject a mismatched cursor with a 400 instead of silently resuming at a
 * position that no longer exists, which would skip or repeat rows with no error anywhere.
 *
 * <p>Encoded as base64url of a small JSON object. The encoding is not a security measure and must
 * not be mistaken for one: it is opaque so that clients do not build on the key's shape, and any
 * client that decodes it is relying on something this class is free to change. A cursor that is
 * malformed, of another version, or of an unexpected shape decodes to empty, and the caller
 * decides what that means — never an exception from here, because a decoder that throws is a
 * decoder that can turn a bad query string into a 500.
 */
public final class CursorCodec {

    private static final int VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private CursorCodec() {
    }

    /**
     * @param ordering an identity for the ordering, not a display name — see the class note
     * @param key      the last row's sort key, one string per component, compared in order
     */
    public record Cursor(int version, String ordering, List<String> key) {
        public Cursor {
            key = List.copyOf(key);
        }
    }

    public static String encode(String ordering, List<String> key) {
        // Hand-built rather than binding a record: the payload is three fixed fields, and a
        // reflective serializer here would be a dependency on field names staying put forever.
        StringBuilder json = new StringBuilder("{\"v\":").append(VERSION)
                .append(",\"o\":\"").append(escape(ordering)).append("\",\"k\":[");
        for (int i = 0; i < key.size(); i++) {
            json.append(i == 0 ? "" : ",").append('"').append(escape(key.get(i))).append('"');
        }
        json.append("]}");
        return ENCODER.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** @return the cursor, or empty if it is not one this version understands */
    public static Optional<Cursor> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = DECODER.decode(cursor);
            JsonNode node = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
            if (!node.isObject() || node.get("v").asInt() != VERSION) {
                return Optional.empty();
            }
            JsonNode ordering = node.get("o");
            JsonNode key = node.get("k");
            if (ordering == null || !ordering.isTextual() || key == null || !key.isArray()) {
                return Optional.empty();
            }
            List<String> parts = key.valueStream()
                    .map(JsonNode::asText)
                    .toList();
            return Optional.of(new Cursor(VERSION, ordering.asText(), parts));
        } catch (RuntimeException e) {
            // Deliberately broad: every failure here means the same thing to the caller, and the
            // one thing this must never do is turn a bad query parameter into a 500.
            return Optional.empty();
        }
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
