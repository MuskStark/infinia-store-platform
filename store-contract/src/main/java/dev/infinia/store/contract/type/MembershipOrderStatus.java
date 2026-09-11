package dev.infinia.store.contract.type;

/** Lifecycle of a membership purchase order. */
public enum MembershipOrderStatus {
    /** Created, waiting for the buyer to pay at the gateway cashier. */
    PENDING,
    /** Gateway confirmed payment; the membership has been applied. */
    PAID,
    /** Payment window elapsed without a confirmation; the buyer may create a new order. */
    CLOSED
}
