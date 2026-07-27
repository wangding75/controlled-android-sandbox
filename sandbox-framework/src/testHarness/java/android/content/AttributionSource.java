package android.content;

/** Host-side fixture exposing the hidden state constructor used by current Android releases. */
public final class AttributionSource {
    private final AttributionSourceState mAttributionSourceState;

    public AttributionSource(AttributionSourceState state) {
        this.mAttributionSourceState = state;
    }

    public AttributionSourceState getStateForTest() {
        return mAttributionSourceState;
    }
}
