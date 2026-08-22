package android.telephony;

import java.util.List;

/** Minimal legacy telephony callback shape used by the static fixture compiler. */
public class PhoneStateListener {
    public static final int LISTEN_NONE = 0;
    public static final int LISTEN_CELL_INFO = 0x400;
    public void onCellInfoChanged(List<CellInfo> cellInfo) { }
}
