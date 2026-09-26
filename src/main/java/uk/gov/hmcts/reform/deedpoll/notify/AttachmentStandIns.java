package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.gov.service.notify.NotificationClientException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a preview puts where a file attached to the template in the Notify portal will go.
 *
 * <p><strong>Why a preview needs this and a send does not.</strong> A file attached to a template in the portal is a
 * placeholder named after that file -- {@code ((LOC021_0226.pdf))} -- and Notify lists it as a required key. A real
 * send fills it in as a download link itself, so api sends no key for it. The preview endpoint does not: asked to
 * render the same personalisation, it answers 400 {@code Missing personalisation: <file names>}. The check page is
 * mandatory, so without a stand-in a template with an attachment could not be sent through admin at all. notify-4a
 * Task 3 measured all three of those facts against the real Notify.
 *
 * <p><strong>The keys are discovered, never written down here.</strong> They are the portal's file names and change
 * when a file is replaced, so a list in api would go stale silently. Two ways to learn them were weighed:
 * <ul>
 *   <li><em>Read the template first</em> ({@code getTemplateById}) and fill each required key api does not own.
 *       That costs a second call on <em>every</em> preview, including the templates with no attachment, and a
 *       preview is already the one operation whose javadoc warns about Notify's rate limit.</li>
 *   <li><em>Read Notify's own refusal and retry once</em> -- this one. A template with no attachment makes exactly
 *       the one call it made before, and one with attachments makes two. It does lean on the wording of Notify's
 *       message, but every way that reading can go wrong fails closed: a message it does not recognise, or a list
 *       it cannot read as file names, yields no stand-ins, and Notify then refuses the preview again. Nothing
 *       here can make a preview pass that Notify would not have passed with the right keys.</li>
 * </ul>
 *
 * <p><strong>A key api owns is never stood in for, and neither is anything that is not a file name.</strong> A
 * template whose {@code ((deed_document))} was misspelt would otherwise preview cleanly and fail every real send at
 * the drain, which is where nobody is watching. So a refusal naming any of the template's
 * {@linkplain EmailTemplate#resolvedKeys() resolved keys}, {@linkplain EmailTemplate#slots() slots} or
 * {@linkplain EmailTemplate#documentKeys() document keys} is left to fail as it did, and so is one naming a key that
 * does not look like an attached file -- {@code deed_documnet} is api's misspelling, not the portal's upload. That
 * second test is a shape rather than a list, so it survives a file being renamed.
 *
 * <p><strong>Nothing here can reach a send.</strong> The stand-ins are written into a copy made for the second
 * preview call and nowhere else; the personalisation a send freezes onto its outbox row is composed without them.
 */
final class AttachmentStandIns {

    /**
     * What the check page shows where the portal's file will be linked.
     *
     * <p>Prose, in the same register as {@link FormsForCompletionPersonalisation#DEED_NOT_YET_GENERATED}: the
     * caseworker reads a sentence saying where the link goes rather than a gap, or a file name that looks like a
     * mistake.
     */
    static final String STAND_IN = "[a download link to this form, added when the email is sent]";

    /** How Notify's 400 begins when a preview lacks a required key, measured against the real endpoint. */
    static final String MISSING_PERSONALISATION = "Missing personalisation: ";

    /**
     * The shape of a file attached in the portal: a name, a dot and a short extension. api's own keys are
     * snake_case and never contain a dot.
     */
    private static final Pattern FILE_NAME = Pattern.compile(".+\\.[A-Za-z0-9]{1,5}");

    private AttachmentStandIns() {
    }

    /**
     * The personalisation to retry a refused preview with, or empty if this refusal is not one a stand-in answers.
     *
     * @param template the template being previewed, whose own keys are never stood in for
     * @param personalisation what the refused preview carried; not modified
     * @param refused Notify's refusal of that preview
     * @param json the mapper to read Notify's error body with
     */
    static Optional<Map<String, Object>> retryWith(
        EmailTemplate template, Map<String, Object> personalisation, NotificationClientException refused,
        ObjectMapper json) {

        if (refused.getHttpResult() != 400) {
            return Optional.empty();
        }
        Set<String> owned = ownedKeys(template);
        List<String> fragments = missingKeys(refused.getMessage(), json);
        boolean anyOneOwned = fragments.stream().anyMatch(
            fragment -> owned.contains(fragment) || personalisation.containsKey(fragment));
        List<String> missing = anyOneOwned ? List.of() : candidateFileNames(fragments);
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> standIns = new LinkedHashMap<>(personalisation);
        missing.forEach(key -> standIns.put(key, STAND_IN));
        return Optional.of(standIns);
    }

    /**
     * The keys a 400 names as missing, or none if it is some other refusal.
     *
     * <p>The client's message is {@code Status code: 400 } followed by Notify's JSON body, so the body is read as
     * JSON rather than matched as text: a refusal about something else, whose wording happened to contain the same
     * phrase, is not mistaken for this one.
     */
    static List<String> missingKeys(String message, ObjectMapper json) {
        if (message == null || message.indexOf('{') < 0) {
            return List.of();
        }
        try {
            JsonNode body = json.readTree(message.substring(message.indexOf('{')));
            for (JsonNode error : body.path("errors")) {
                String text = error.path("message").asText("");
                if (text.startsWith(MISSING_PERSONALISATION)) {
                    return Arrays.stream(text.substring(MISSING_PERSONALISATION.length()).split(", "))
                        .map(String::strip)
                        .filter(key -> !key.isEmpty())
                        .toList();
                }
            }
            return List.of();
        } catch (JsonProcessingException unreadable) {
            return List.of();
        }
    }

    /**
     * Every file name Notify's list could be naming, or none if the list cannot be read as file names at all.
     *
     * <p>Notify joins the missing keys with {@code ", "} and a portal file name may itself contain one, so the list
     * is ambiguous: {@code LOC021.pdf, Welsh version.pdf} is two files or one, and the text alone cannot say which.
     * So no reading is chosen -- every run of consecutive fragments that is file-shaped gets a stand-in, and Notify,
     * which ignores personalisation a template does not use, takes whichever of them it asked for. The list must
     * still divide wholly into file-shaped runs, or it is not a list of attachments and nothing is stood in for. A
     * fragment that is a key api owns is refused before this is reached, so it is never swallowed into a file name.
     */
    static List<String> candidateFileNames(List<String> fragments) {
        int count = fragments.size();
        // divides[i]: the fragments from i onwards can be read wholly as file names.
        boolean[] divides = new boolean[count + 1];
        divides[count] = true;
        Set<String> candidates = new LinkedHashSet<>();
        for (int from = count - 1; from >= 0; from--) {
            for (int to = from + 1; to <= count; to++) {
                String run = String.join(", ", fragments.subList(from, to));
                if (FILE_NAME.matcher(run).matches()) {
                    candidates.add(run);
                    divides[from] |= divides[to];
                }
            }
        }
        return count > 0 && divides[0] ? List.copyOf(candidates) : List.of();
    }

    private static Set<String> ownedKeys(EmailTemplate template) {
        Set<String> owned = new HashSet<>(template.personalisationKeys());
        owned.addAll(template.documentKeys());
        return owned;
    }
}
