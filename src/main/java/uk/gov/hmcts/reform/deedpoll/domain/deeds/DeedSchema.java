package uk.gov.hmcts.reform.deedpoll.domain.deeds;

/**
 * The shape the content controls' XPath expects of the bound data part, read off the template itself.
 *
 * <p>Every binding in both templates reads
 * {@code /ns0:DocumentTemplate[1]/deedpoll_deedpoll[1]/<field>[1]} with {@code ns0} mapped to
 * {@code urn:microsoft-crm/document-template/deedpoll_deedpoll/10740/} -- {@code 10740} being the Dataverse
 * entity type code. <strong>Those three values are read from the template rather than stated as constants
 * here</strong>, because getting any of them wrong resolves every binding to empty with no error anywhere: a
 * constant that had gone stale against a re-authored template would produce a blank deed, and a value read from
 * the template cannot. {@code DeedControls} requires all of a template's bindings to agree on this, which is
 * what makes one instance the answer for the whole document.
 *
 * <p><strong>The namespace reset on the entity element is load-bearing.</strong> Only the root is in the
 * namespace; the XPath names {@code deedpoll_deedpoll} and the fields with no prefix, so they must be in no
 * namespace at all. Hence the {@code xmlns=""} {@link DeedDataPart} writes.
 *
 * @param namespaceUri what the binding's {@code w:prefixMappings} maps its prefix to
 * @param rootElement the prefixed element at the root of the XPath, {@code DocumentTemplate}
 * @param entityElement the unprefixed element below it, {@code deedpoll_deedpoll}
 */
record DeedSchema(String namespaceUri, String rootElement, String entityElement) {
}
