package android.content;

/** Host-side fixture matching the AOSP field shape needed by the clean-room proxy test. */
public final class AttributionSourceState {
    public int uid;
    public int pid;
    public String packageName;
    public String attributionTag;
    public String token;
    public AttributionSourceState[] next;

    public AttributionSourceState() {
    }
}
