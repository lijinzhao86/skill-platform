package com.skillmasterai.support;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Builds a {@code multipart/form-data} body for an {@link java.net.http.HttpClient} request.
 *
 * <p>Hand-rolled because {@link java.net.http.HttpClient} has no multipart support and adding a
 * client library to send one part would cost more than the format does. That format is small and
 * fixed: a boundary, then per part a {@code Content-Disposition} header, a blank line, the bytes,
 * and a CRLF; then the same boundary with two trailing dashes.
 *
 * <p>One instance is one request — the boundary is generated per instance, so a body built by one
 * cannot be interpreted against another's {@code Content-Type}.
 */
public final class Multipart {

    private static final String CRLF = "\r\n";

    private final String boundary = "----skillmaster-" + UUID.randomUUID();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    private Multipart() {
    }

    public static Multipart create() {
        return new Multipart();
    }

    public Multipart file(String field, String filename, byte[] content) {
        write("--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"" + CRLF
                + "Content-Type: application/octet-stream" + CRLF + CRLF);
        body.writeBytes(content);
        write(CRLF);
        return this;
    }

    /** The value for the {@code Content-Type} header, boundary included. */
    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        ByteArrayOutputStream complete = new ByteArrayOutputStream(body.size() + 128);
        complete.writeBytes(body.toByteArray());
        complete.writeBytes(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArray(complete.toByteArray());
    }

    private void write(String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
