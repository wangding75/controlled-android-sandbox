package com.warden.controlledsandbox.runtime.provider;

import android.os.Bundle;
import java.util.ArrayList;

/**
 * Optional extension for Providers that need CALL operations inside an atomic batch.
 * Implementations must either apply every operation and return a complete result or throw
 * without exposing a partial commit.
 */
public interface AtomicProviderBatch {
    Bundle applyAtomicBatch(String authority, ArrayList<Bundle> operations) throws Exception;
}
