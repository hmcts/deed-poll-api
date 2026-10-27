package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.List;

/**
 * Everything a Master is shown for one referral: the referral, the application it was raised on, and that
 * application's readable documents. No notes or witnesses since 2026-10-27 (Master Sullivan's feedback).
 *
 * <p>All of it in one record because it is one read. A judge opening a referral needs the reasons it was
 * raised for, the names the application is about and its documents -- and each of those coming back from its own
 * endpoint would mean the visibility check ({@code JudicialReferralService
 * .visibleTo}) had to be repeated on every one of them, which is the arrangement where one of them eventually
 * does not repeat it.
 *
 * <p><strong>The application is carried rather than navigated to.</strong> It is the same object as
 * {@code referral.getDeedPoll()}, and it is a component here so that a caller never has to reach through the
 * entity association to get it: {@code Referral.deedPoll} is {@code LAZY} and
 * {@code spring.jpa.open-in-view} is false, so a caller that navigated it outside the domain's transaction
 * would be depending on it having been initialised somewhere it cannot see.
 * {@code ReferralRepository.findWithCaseById} join-fetches it for that reason.
 *
 * <p><strong>Entities, not view models</strong>, as every other service in this domain returns -- mapping to the
 * judicial DTOs is the controller's job, and what is left out is {@code JudicialApplicationView}'s. So this record
 * carries the whole case: what a judge may <em>see</em> is decided by the DTO one
 * layer out, and what a judge may <em>reach</em> is decided by the visibility check one layer in.
 *
 * @param referral the referral this judge asked for, and has been found permitted to see
 * @param application the case it was raised on, resolved from the referral rather than named by the caller
 * @param documents the application's documents a download would serve, oldest first, with their types
 *     initialised -- {@code DocumentService.readableDocuments}, never every row
 */
public record JudicialReferralView(Referral referral,
                                   DeedPoll application,
                                   List<DeedPollSupportingDocument> documents) {

    public JudicialReferralView {
        documents = List.copyOf(documents);
    }
}
