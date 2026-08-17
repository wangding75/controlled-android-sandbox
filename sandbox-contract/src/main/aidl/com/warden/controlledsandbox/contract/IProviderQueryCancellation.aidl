package com.warden.controlledsandbox.contract;

/**
 * Session-scoped cancellation channel for a Provider operation that is still in flight.
 *
 * <p>This is deliberately separate from Cursor close/cancel.  A Cursor token does not exist
 * until the target Provider has returned, while Android callers are allowed to cancel the
 * query while that call is still executing.</p>
 */
interface IProviderQueryCancellation {
    void attach(IProviderQueryCancellation endpoint);
    void cancel();
    void detach();
}
