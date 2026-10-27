package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Which {@code DocumentStore} holds supporting documents, and what the CDAM one writes into a case's document URLs.
 *
 * <p>The store is chosen by {@code @ConditionalOnProperty} on each implementation, and this record is the refusal
 * beside it: {@code filesystem} and {@code cdam} exactly, and anything else stops startup. Spring's condition matches
 * case-insensitively and a misspelling matches neither store, so without this a typo would start the service with no
 * store at all, or with one the operator did not name.
 *
 * @param store           {@link #FILESYSTEM} or {@link #CDAM}
 * @param documentUrlBase the dm-store base a document's URL is written under in the case's document event; see
 *                        {@code application.yaml}. Only the CDAM store reads it
 */
@ConfigurationProperties(prefix = "deedpoll.documents")
public record DocumentStoreProperties(String store, String documentUrlBase) {

    /** The property that selects the store, for the implementations' conditions. */
    public static final String STORE_PROPERTY = "deedpoll.documents.store";

    public static final String FILESYSTEM = "filesystem";

    public static final String CDAM = "cdam";

    public DocumentStoreProperties {
        if (store == null) {
            store = FILESYSTEM;
        }
        if (!FILESYSTEM.equals(store) && !CDAM.equals(store)) {
            throw new IllegalArgumentException(
                STORE_PROPERTY + " must be " + FILESYSTEM + " or " + CDAM + ", not '" + store + "'");
        }
        if (documentUrlBase == null || documentUrlBase.isBlank()) {
            documentUrlBase = "http://dm-store:8080";
        }
        // Parsed only to refuse a value that is not a URL; the trailing slash goes so a path can be appended to it.
        URI.create(documentUrlBase);
        documentUrlBase = documentUrlBase.endsWith("/")
            ? documentUrlBase.substring(0, documentUrlBase.length() - 1)
            : documentUrlBase;
    }

    /** Whether documents go to CDAM. */
    public boolean isCdam() {
        return CDAM.equals(store);
    }
}
