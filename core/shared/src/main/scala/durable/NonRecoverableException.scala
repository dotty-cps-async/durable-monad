package durable

/**
 * Marker trait for exceptions that should not trigger retries.
 *
 * Activities throwing NonRecoverableException will fail immediately
 * without retry attempts, since the error is due to configuration
 * or logic issues that won't be fixed by retrying.
 */
trait NonRecoverableException
