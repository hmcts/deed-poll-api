package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversionCapacityTest {

    private final ConversionCapacity capacity = new ConversionCapacity(3, Duration.ofSeconds(5));

    @Test
    @DisplayName("three conversions run, and a fourth is refused at once with a bounded wait")
    void fourthIsBusy() {
        ConversionCapacity.Permit tiff = capacity.acquire(PreviewFormat.TIFF);
        final ConversionCapacity.Permit docx = capacity.acquire(PreviewFormat.DOCX);
        final ConversionCapacity.Permit png = capacity.acquire(PreviewFormat.PNG);
        assertThat(capacity.available()).isZero();

        assertThatThrownBy(() -> capacity.acquire(PreviewFormat.JPEG)).isInstanceOfSatisfying(PreviewError.class,
            error -> {
                assertThat(error.failure()).isEqualTo(PreviewFailure.BUSY);
                assertThat(error.retryAfter()).contains(Duration.ofSeconds(5));
            });

        tiff.close();
        capacity.acquire(PreviewFormat.JPEG).close();
        docx.close();
        png.close();
        assertThat(capacity.available()).isEqualTo(3);
    }

    @Test
    @DisplayName("a PDF passes through on a full pod and takes no permit")
    void pdfTakesNothing() {
        List<ConversionCapacity.Permit> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(capacity.acquire(PreviewFormat.DOCX));
        }
        try (ConversionCapacity.Permit pdf = capacity.acquire(PreviewFormat.PDF)) {
            assertThat(capacity.available()).isZero();
        }
        assertThat(capacity.available()).isZero();
        held.forEach(ConversionCapacity.Permit::close);
        assertThat(capacity.available()).isEqualTo(3);
    }

    @Test
    @DisplayName("closing a permit twice releases it once, so a double cleanup cannot widen the pod")
    void releasedOnce() {
        ConversionCapacity.Permit permit = capacity.acquire(PreviewFormat.TIFF);
        permit.close();
        permit.close();
        assertThat(capacity.available()).isEqualTo(3);
    }

    @Test
    @DisplayName("concurrent requests never hold more than the configured permits")
    void concurrentAdmission() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch refusals = new CountDownLatch(5);
        try {
            List<Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try (ConversionCapacity.Permit permit = capacity.acquire(PreviewFormat.TIFF)) {
                        hold.await(5, TimeUnit.SECONDS);
                        return true;
                    } catch (PreviewError busy) {
                        refusals.countDown();
                        return false;
                    }
                }));
            }
            start.countDown();
            // Three are admitted and hold their permits; the other five are refused at once rather than waiting.
            assertThat(refusals.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(capacity.available()).isZero();
            hold.countDown();
            long admitted = 0;
            for (Future<Boolean> attempt : attempts) {
                admitted += attempt.get(5, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(admitted).isEqualTo(3);
            assertThat(capacity.available()).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("workspace slots bound every format, a PDF's included, apart from the conversion permits")
    void workspacesBoundEveryFormat() {
        ConversionCapacity two = new ConversionCapacity(3, 2, Duration.ofSeconds(5));
        final ConversionCapacity.Permit first = two.acquireWorkspace();
        final ConversionCapacity.Permit second = two.acquireWorkspace();
        assertThat(two.availableWorkspaces()).isZero();
        assertThat(two.available()).isEqualTo(3);

        assertThatThrownBy(two::acquireWorkspace).isInstanceOfSatisfying(PreviewError.class, error -> {
            assertThat(error.failure()).isEqualTo(PreviewFailure.BUSY);
            assertThat(error.retryAfter()).contains(Duration.ofSeconds(5));
        });

        first.close();
        first.close();
        assertThat(two.availableWorkspaces()).isEqualTo(1);
        second.close();
        assertThat(two.availableWorkspaces()).isEqualTo(2);
        assertThat(capacity.availableWorkspaces()).isEqualTo(PreviewProperties.MAX_CONCURRENT_PREVIEWS);
    }

    @Test
    @DisplayName("a pod with no permits or no workspaces refuses to start")
    void atLeastOne() {
        assertThatThrownBy(() -> new ConversionCapacity(0, Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversionCapacity(3, 0, Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
