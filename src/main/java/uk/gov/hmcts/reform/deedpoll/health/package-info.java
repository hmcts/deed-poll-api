/**
 * What Kubernetes is told about this pod.
 *
 * <p>Two probes, and the difference between them is the whole content of this package.
 * <strong>Readiness</strong> asks "should traffic come here", and is allowed to depend on the database,
 * because a pod whose schema is missing cannot answer a request. <strong>Liveness</strong> asks "should this
 * process be killed and restarted", and depends on nothing: the process answering is the signal. Putting the
 * database in liveness is the classic way to turn a database blip into a rolling restart of every replica,
 * which cannot fix someone else's database and removes the pods that would have recovered on their own.
 *
 * <p>So the liveness group is empty of anything but {@code livenessState}, and that emptiness is configured
 * explicitly in {@code application.yaml} rather than left to Spring Boot's default, so that adding a check to
 * the wrong group is a visible edit.
 */
package uk.gov.hmcts.reform.deedpoll.health;
