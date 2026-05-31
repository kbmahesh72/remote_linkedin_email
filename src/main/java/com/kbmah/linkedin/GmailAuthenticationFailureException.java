package com.kbmah.linkedin;

final class GmailAuthenticationFailureException extends RuntimeException {
    GmailAuthenticationFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
