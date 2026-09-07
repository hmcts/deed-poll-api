package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The per-year reference-number counter: {@code deedpoll_referencecounter}, ported from the
 * {@code ReferenceCounter} model in {@code reference/deed-poll.prisma}.
 *
 * <p>Keyed by year alone, because the serial is shared across KBDP and KBDR within a year. The row
 * exists so allocation can be a locked read-modify-write inside a transaction rather than the
 * source flow's read-the-maximum-and-add-one, which races under concurrent creates. The id is the
 * year itself and is supplied by the caller, so there is no {@code @GeneratedValue} here.
 */
@Entity
@Table(name = "deedpoll_referencecounter")
@Getter
@Setter
public class ReferenceCounter {

    @Id
    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "lastSerial", nullable = false)
    private int lastSerial;
}
