package android.media;

/** Test fixture for the static AudioManager service cache used on API32 and API35. */
public final class AudioManager {
    private static IAudioService sService;

    public AudioManager() { }

    public static void resetForTest(IAudioService service) {
        sService = service;
    }

    public static IAudioService serviceForTest() {
        return sService;
    }
}
