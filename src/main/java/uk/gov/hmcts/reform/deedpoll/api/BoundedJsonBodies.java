package uk.gov.hmcts.reform.deedpoll.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * A JSON body read by hand, once the caller has been checked, rather than bound by {@code @RequestBody}: no more than
 * a fixed number of bytes is ever held (413 beyond), and only {@code application/json} or an
 * {@code application/*+json} is decoded (415 otherwise). Shared by the two deletion admissions, which must refuse a
 * caller before anything they sent is read.
 *
 * <p>An absent body is null, which the caller's own validation refuses field by field. Unparseable JSON is the
 * service's ordinary unreadable-body 400, which names nothing the caller sent.
 */
public final class BoundedJsonBodies {

    private BoundedJsonBodies() {
    }

    /**
     * The body as {@code type}, or null when there is none.
     *
     * @param maxBytes the most of the body that is ever read
     */
    public static <T> T read(HttpServletRequest http, ObjectMapper json, Class<T> type, int maxBytes)
        throws HttpMediaTypeNotSupportedException {
        refuseForm(http.getHeader(HttpHeaders.CONTENT_TYPE));
        byte[] raw;
        try (InputStream in = http.getInputStream()) {
            raw = in.readNBytes(maxBytes + 1);
        } catch (IOException unreadable) {
            throw new HttpMessageNotReadableException("The request body could not be read", unreadable, null);
        }
        if (raw.length == 0) {
            return null;
        }
        if (raw.length > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        requireJson(http.getHeader(HttpHeaders.CONTENT_TYPE));
        try {
            return json.readValue(raw, type);
        } catch (IOException unreadable) {
            throw new HttpMessageNotReadableException("The request body is not valid JSON", unreadable, null);
        }
    }

    /**
     * Refuses 415 for a form body before the stream is read. The dispatcher has already parsed a multipart body into
     * parts, so its stream reads as empty and would otherwise reach the field-by-field 400s; a urlencoded body is
     * refused alike, so neither form is ever answered as though it were a missing JSON body.
     */
    private static void refuseForm(String header) throws HttpMediaTypeNotSupportedException {
        if (header == null) {
            return;
        }
        String type = header.trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("multipart/") || type.startsWith("application/x-www-form-urlencoded")) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, List.of(MediaType.APPLICATION_JSON));
        }
    }

    /**
     * Refuses 415 unless the body is {@code application/json} or an {@code application/*+json}.
     */
    private static void requireJson(String header) throws HttpMediaTypeNotSupportedException {
        List<MediaType> supported = List.of(MediaType.APPLICATION_JSON);
        if (header == null || header.isBlank()) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, supported);
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(header);
        } catch (InvalidMediaTypeException unparseable) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, supported);
        }
        boolean isJson = "application".equals(type.getType())
            && ("json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json"));
        if (!isJson) {
            throw new HttpMediaTypeNotSupportedException(type, supported);
        }
    }
}
