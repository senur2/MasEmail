package fr.mas.email.contract;

/** A draft is never an authorization to send an email. */
public record DraftResponse(String emailId, String planId, String subject, String body, Status status) {
    public enum Status { DRAFT }

    public DraftResponse {
        emailId = Checks.text(emailId, "emailId"); planId = Checks.text(planId, "planId");
        subject = Checks.text(subject, "subject"); body = Checks.text(body, "body");
        Checks.required(status, "status");
    }
}
