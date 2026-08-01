package com.warden.controlledsandbox.contract;

import android.os.Parcelable;
import java.util.List;

/** Common Java view implemented by all typed Binder page parcelables. */
public interface VirtualPageView<T extends Parcelable> {
    List<T> items();
    List<VirtualPageBlob> blobs();
    String nextPageToken();
    long snapshotRevision();
}
