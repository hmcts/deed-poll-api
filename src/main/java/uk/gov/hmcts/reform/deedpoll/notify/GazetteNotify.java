package uk.gov.hmcts.reform.deedpoll.notify;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteFileEvidence;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequest;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GeneratedGazetteSpreadsheet;
import uk.gov.service.notify.Authentication;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.RetentionPeriodDuration;
import uk.gov.service.notify.SendEmailResponse;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gazette-only transport: one HTTP attempt, no redirects, bounded calls, no provider prose in failures. */
@Service
public class GazetteNotify {
    private final NotificationClient sdk;
    private final OkHttpClient http = transport(Duration.ofSeconds(60));

    public GazetteNotify(NotificationClient sdk) {
        this.sdk = sdk;
    }

    static OkHttpClient transport(Duration deadline) {
        return new OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30)).callTimeout(deadline).build();
    }

    /** Local encoding only: SDK prepareUpload builds JSON/Base64 and performs no network upload. */
    public Prepared prepare(GazetteRequest row, List<GeneratedGazetteSpreadsheet> files) {
        List<GazetteFileEvidence> evidence = new ArrayList<>();
        for (GeneratedGazetteSpreadsheet file : files) {
            String kind = switch (file.filename()) {
                case "gazette-adult.xlsx" -> "adult";
                case "gazette-minor.xlsx" -> "minor";
                default -> throw new IllegalArgumentException("Unexpected Gazette file");
            };
            byte[] bytes = file.content();
            evidence.add(new GazetteFileEvidence(kind, file.caseCount(), bytes.length, sha256(bytes)));
        }
        int expectedFiles = (row.adultCount() > 0 ? 1 : 0) + (row.minorCount() > 0 ? 1 : 0);
        if (evidence.size() != expectedFiles
            || evidence.stream().map(GazetteFileEvidence::kind).distinct().count() != expectedFiles
            || evidence.stream().anyMatch(file -> file.caseCount() != (file.kind().equals("adult")
                ? row.adultCount() : row.minorCount()))) {
            throw new IllegalArgumentException("Incomplete Gazette files");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("case_count", row.adultCount() + row.minorCount());
        for (int index = 0; index < files.size(); index++) {
            GeneratedGazetteSpreadsheet file = files.get(index);
            try {
                values.put(evidence.get(index).kind() + "_spreadsheet", NotificationClient.prepareUpload(
                    file.content(), file.filename(), true,
                    new RetentionPeriodDuration(row.configuration().retentionWeeks(), ChronoUnit.WEEKS)));
            } catch (NotificationClientException refused) {
                throw new Failure(0);
            }
        }
        return new Prepared(evidence, values);
    }

    public Receipt send(GazetteRequest row, Prepared prepared) {
        return send(row, prepared, http);
    }

    // Package seam for real HTTP fault tests with a short deadline; production always uses the bounded client above.
    Receipt send(GazetteRequest row, Prepared prepared, OkHttpClient client) {
        JSONObject body = new JSONObject().put("template_id", row.configuration().templateId().toString())
            .put("email_address", row.recipientEmail()).put("reference", row.id().toString())
            .put("email_reply_to_id", row.configuration().replyToId().toString())
            .put("personalisation", new JSONObject(prepared.values()));
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        RequestBody payload = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/json; charset=utf-8");
            }

            @Override
            public long contentLength() {
                return bytes.length;
            }

            @Override
            public boolean isOneShot() {
                return true; // Also prevents HTTP 408/503/421 follow-up requests in OkHttp.
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.write(bytes);
            }
        };
        Request request = new Request.Builder().url(sdk.getBaseUrl() + "/v2/notifications/email")
            .header("Authorization", "Bearer " + new Authentication().create(sdk.getServiceId(), sdk.getApiKey()))
            .header("User-Agent", sdk.getUserAgent()).header("Accept", "application/json").post(payload).build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != 201) {
                // Do not read or log an error body: it can echo personalisation or credentials.
                throw new Failure(response.code());
            }
            if (response.body() == null) {
                throw new Failure(0);
            }
            byte[] content = response.body().byteStream().readNBytes(1024 * 1024 + 1);
            if (content.length > 1024 * 1024) {
                throw new Failure(0);
            }
            SendEmailResponse accepted = new SendEmailResponse(new String(content, StandardCharsets.UTF_8));
            if (accepted.getNotificationId() == null || accepted.getTemplateVersion() < 1
                || !row.configuration().templateId().equals(accepted.getTemplateId())
                || !accepted.getReference().equals(java.util.Optional.of(row.id().toString()))) {
                throw new Failure(0);
            }
            return new Receipt(accepted.getNotificationId(), accepted.getTemplateVersion());
        } catch (IOException | IllegalArgumentException | org.json.JSONException failed) {
            throw new Failure(0);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Prepared(List<GazetteFileEvidence> evidence, Map<String, Object> values) {
        public Prepared {
            evidence = List.copyOf(evidence);
            values = Map.copyOf(values);
        }

        @Override
        public String toString() {
            return "Prepared Gazette files";
        }
    }

    public record Receipt(UUID notificationId, int templateVersion) { }

    public static final class Failure extends RuntimeException {
        private final int status;

        public Failure(int status) {
            super("Gazette Notify call did not return a verified acceptance");
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
