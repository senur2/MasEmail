package fr.mas.email.contract;

/** Well-formed data that violates an application rule; never contains untrusted content. */
public final class InvalidOutputException extends IllegalArgumentException {
    public InvalidOutputException() { super("Contract output violates a business invariant"); }
    public InvalidOutputException(String message) { super(message); }
}
