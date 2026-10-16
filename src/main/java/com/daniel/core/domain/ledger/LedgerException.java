package com.daniel.core.domain.ledger;

/** Domain rejection. {@link #field()} names the offending input so forms can focus it. */
public class LedgerException extends RuntimeException {

    private final String field;

    public LedgerException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
