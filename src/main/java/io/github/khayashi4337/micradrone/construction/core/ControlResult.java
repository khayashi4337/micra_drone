package io.github.khayashi4337.micradrone.construction.core;

/** What an owner's or operator's control call (cancel, resume) came back with. */
public enum ControlResult {
    OK,
    NOT_FOUND,
    NOT_ALLOWED,
    WRONG_STATE
}
