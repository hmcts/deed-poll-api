package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Registers the store properties and environment guard, and CDAM's Feign client when the CDAM store is selected.
 *
 * <p>{@link CaseDocumentClientApi} is registered here and nowhere else, at {@code case_document_am.url}. The client
 * library's own {@code CaseDocumentManagementClientAutoConfiguration} would scan its whole package and add a health
 * indicator calling CDAM -- which the unauthenticated {@code /health} aggregates, so a CDAM outage would read as this
 * service being down, the reason {@code application.yaml} excludes the CCD client's. That one is listed only in
 * {@code spring.factories}, which Boot 3 does not read, so it never loads; it is not excluded because excluding a
 * class Boot does not know as an auto-configuration fails startup.
 */
@Configuration
@EnableConfigurationProperties(DocumentStoreProperties.class)
@Import(DocumentStoreEnvironmentGuard.class)
public class DocumentStoreConfiguration {

    /** CDAM's client, only when documents go to CDAM. */
    @Configuration
    @ConditionalOnProperty(name = DocumentStoreProperties.STORE_PROPERTY, havingValue = DocumentStoreProperties.CDAM)
    @EnableFeignClients(clients = CaseDocumentClientApi.class)
    static class Cdam {
    }
}
