package android.telephony;

import java.util.List;

/** Minimal API 31 callback shape used by the package-neutral fixture compiler. */
public class TelephonyCallback {
    public interface CellInfoListener {
        void onCellInfoChanged(List<CellInfo> cellInfo);
    }
}
