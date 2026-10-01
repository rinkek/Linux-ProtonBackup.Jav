package protonbackup.ui.service;

/** How a sign-in attempt ended; {@code message} is for the user. */
public record SignInOutcome(boolean success, String message) {}
