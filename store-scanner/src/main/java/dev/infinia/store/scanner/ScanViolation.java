package dev.infinia.store.scanner;

/** Thrown when safe extraction limits are violated; maps to a blocking finding. */
public class ScanViolation extends RuntimeException {

    public final String rule;

    public ScanViolation(String rule, String message) {
        super(message);
        this.rule = rule;
    }
}
