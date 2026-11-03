package net.ptcrys.fpsmatch.editor.core.project;

/**
 * Internal budgets of the save queue. They mirror the operational defaults of the storage module
 * ({@code StoreLimits.DEFAULT}) and the limits frozen for this task; they are not a second,
 * independent policy. A published manifest must satisfy the storage budgets, so the queue refuses a
 * candidate that the storage layer could not publish anyway, and it does so before any I/O.
 */
final class Budgets {

    /** Metadata text ceiling, identical to the storage schema's 4096 characters. */
    static final int MAX_TEXT_LENGTH = 4096;

    /** Module additions accepted in one delta, parallel to the project module budget. */
    static final int MAX_MODULES = 1024;

    /** Documents plus assets a published manifest may contain. */
    static final int MAX_ENTRIES = 8192;

    /** Dependency edges a published manifest may contain. */
    static final int MAX_DEPENDENCIES = 65536;

    /** Entries the queue may hold across all queued deltas. */
    static final int MAX_PENDING_ENTRIES = 65536;

    /** Bounds of the frozen constructor parameter {@code capacity}. */
    static final int MIN_CAPACITY = 1;
    static final int MAX_CAPACITY = 64;

    private Budgets() {}
}
