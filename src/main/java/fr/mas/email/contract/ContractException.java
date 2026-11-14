package fr.mas.email.contract;

/** Deliberately excludes raw JSON, which may contain private email content or secrets. */
public final class ContractException extends RuntimeException {
    ContractException(String message) { super(message); }
    public ContractException() {
        super("JSON does not match the expected structural contract");
    }
}
