/**
 * The saved-query filters above the case list, porting the Dynamics system views.
 *
 * In the domain rather than on the page because each one is a `where` clause — a statement about
 * which states count as "open", which is a business rule the list page merely renders. The
 * prototype had them as a module-level constant in its pages controller, which meant the API could
 * not offer the same filters without restating them.
 *
 * `all` deliberately has no `where`: `findDeedPolls(undefined)` returns everything, and an empty
 * object would be an equivalent but noisier way of saying so.
 */
import { DeedPollState, type Prisma } from "@hmcts/postgres-prisma";

export interface ListFilter {
  key: string;
  label: string;
  where?: Prisma.DeedPollWhereInput;
}

/**
 * Order matters: it is the order the tabs render in, and the first entry is the fallback for an
 * unrecognised key.
 *
 * "Open" is defined by exclusion — everything not yet finished — rather than by listing the active
 * states. That way a state added to the enum later shows up as open by default, which is the safe
 * direction: a new state appearing in a caseworker's open list is noticeable, whereas one silently
 * missing from it is not.
 */
export const LIST_FILTERS: ListFilter[] = [
  { key: "all", label: "All applications" },
  { key: "open", label: "Open", where: { state: { notIn: [DeedPollState.Enroled, DeedPollState.Returned] } } },
  { key: "received", label: "Received", where: { state: DeedPollState.Received } },
  { key: "enroled", label: "Enroled", where: { state: DeedPollState.Enroled } },
  { key: "returned", label: "Returned", where: { state: DeedPollState.Returned } }
];

/**
 * Resolve a filter key from a query string, falling back to the first filter.
 *
 * Falls back rather than throwing because the key comes from a URL a caseworker may have edited or
 * bookmarked against an older version. A 400 on `?filter=nonsense` would be technically defensible
 * and useless: the list is the service's front page, and showing everything is a safe answer.
 */
export function filterFor(key: unknown): ListFilter {
  return LIST_FILTERS.find((filter) => filter.key === key) ?? LIST_FILTERS[0];
}

/** The filters as the tab bar needs them — no `where`, which is not the template's business. */
export function filterTabs(): { key: string; label: string }[] {
  return LIST_FILTERS.map(({ key, label }) => ({ key, label }));
}
