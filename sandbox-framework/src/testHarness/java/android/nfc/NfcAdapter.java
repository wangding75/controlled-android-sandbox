package android.nfc;

import android.content.Context;

/** Minimal static NFC adapter cache fixture for framework hook self-tests. */
public final class NfcAdapter {
    public static INfcAdapter sService;

    private NfcAdapter() { }

    public static NfcAdapter getDefaultAdapter(Context context) {
        return sService == null ? null : new NfcAdapter();
    }
}
