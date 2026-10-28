package uk.gov.hmcts.reform.deedpoll.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every write mapping on the staff surfaces either records an audit action or is a named exemption.
 *
 * <p>The guard behind the rule that a new write path must call {@code AuditTrail}: a {@code POST}, {@code PUT},
 * {@code PATCH} or {@code DELETE} under {@code api.caseworker} or {@code api.judicial} has to carry
 * {@link AuditedAs}, or be one of the four operations below, which change no case state. The annotation is a
 * statement rather than the recording, so the service tests are what prove each action is written.
 *
 * <p>Found by a classpath scan of the two packages rather than a whole-codebase ArchUnit import, which the test
 * JVM's heap does not afford.
 */
class WriteEndpointAuditCoverageTest {

    /**
     * The write mappings that record nothing, and why each may: two previews that send nothing, and one caller's
     * own list of saved queries, which is a preference rather than a change to a query.
     */
    static final Set<String> EXEMPT = Set.of(
        "POST /caseworker/cases/{id}/emails/{template}/preview",
        "POST /caseworker/gazette-notice-previews",
        "PUT /caseworker/saved-queries/{id}/selection",
        "DELETE /caseworker/saved-queries/{id}/selection");

    private static final Set<RequestMethod> WRITES =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);

    private static final List<String> STAFF_PACKAGES =
        List.of("uk.gov.hmcts.reform.deedpoll.api.caseworker", "uk.gov.hmcts.reform.deedpoll.api.judicial");

    @Test
    @DisplayName("every staff write mapping records an audit action or is exempt")
    void everyWriteIsClassified() {
        Map<String, Write> writes = staffWrites();

        assertThat(writes.values()).filteredOn(Write::unclassified).extracting(Write::route).isEmpty();
    }

    @Test
    @DisplayName("all 32 current write mappings are classified, 28 audited and 4 exempt")
    void theCurrentMappingsAreCounted() {
        Map<String, Write> writes = staffWrites();

        assertThat(writes).hasSize(32);
        assertThat(writes.values()).filteredOn(write -> write.action() != null).hasSize(28);
        assertThat(writes.keySet()).containsAll(EXEMPT);
    }

    @Test
    @DisplayName("an exempt mapping does not also claim an action")
    void anExemptionIsNotAlsoAudited() {
        assertThat(staffWrites().values())
            .filteredOn(write -> EXEMPT.contains(write.route()))
            .allSatisfy(write -> assertThat(write.action()).as(write.route()).isNull());
    }

    @Test
    @DisplayName("an unclassified write mapping is caught, and a read or a classified write is not")
    void catchesTheFixture() {
        List<Write> writes = writesOf(UnclassifiedWriteFixture.class);

        assertThat(writes).extracting(Write::route)
            .containsExactlyInAnyOrder("POST /caseworker/fixture/classified", "DELETE /caseworker/fixture/{id}");
        assertThat(writes).filteredOn(Write::unclassified).extracting(Write::route)
            .containsExactly("DELETE /caseworker/fixture/{id}");
    }

    /** Every write mapping of every staff controller, by route; a route claimed twice fails here. */
    private static Map<String, Write> staffWrites() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Write> writes = new TreeMap<>();
        for (String staffPackage : STAFF_PACKAGES) {
            for (BeanDefinition candidate : scanner.findCandidateComponents(staffPackage)) {
                Class<?> controller = ClassUtils.resolveClassName(candidate.getBeanClassName(), null);
                for (Write write : writesOf(controller)) {
                    assertThat(writes.put(write.route(), write)).as("mapped twice: " + write.route()).isNull();
                }
            }
        }
        return writes;
    }

    private static List<Write> writesOf(Class<?> controller) {
        RequestMapping parent = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String prefix = parent == null || parent.path().length == 0 ? "" : parent.path()[0];
        List<Write> writes = new ArrayList<>();
        Method[] methods = controller.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String path = prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]);
            AuditedAs audited = method.getAnnotation(AuditedAs.class);
            for (RequestMethod verb : mapping.method()) {
                if (WRITES.contains(verb)) {
                    writes.add(new Write(verb + " " + path, audited == null ? null : audited.value()));
                }
            }
        }
        return writes;
    }

    private record Write(String route, AuditAction action) {
        boolean unclassified() {
            return action == null && !EXEMPT.contains(route);
        }
    }
}
