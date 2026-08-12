package com.warden.controlledsandbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Product-facing instance cards; engineering probes are intentionally absent from this surface. */
final class PackageAdapter extends BaseAdapter {
    interface Listener {
        void onLaunch(SandboxItem item);
        void onSettings(SandboxItem item);
        void onClone(SandboxItem item);
        void onClear(SandboxItem item);
        void onDelete(SandboxItem item);
    }

    private final LayoutInflater inflater;
    private final PackageManager packageManager;
    private final Listener listener;
    private final List<SandboxItem> items = new ArrayList<>();

    PackageAdapter(Context context, Listener listener) {
        inflater = LayoutInflater.from(context);
        packageManager = context.getPackageManager();
        this.listener = listener;
    }

    void replace(List<SandboxItem> values) {
        items.clear();
        items.addAll(values);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public SandboxItem getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView == null ? inflater.inflate(R.layout.row_package, parent, false) : convertView;
        SandboxItem item = getItem(position);
        SandboxRecord record = item.record;
        SandboxInstance instance = item.instance;
        ((ImageView) view.findViewById(R.id.appIcon)).setImageDrawable(icon(record));
        ((TextView) view.findViewById(R.id.title)).setText(record.label + " · " + instance.displayName);
        ((TextView) view.findViewById(R.id.summary)).setText(record.packageName + "\n实例 " + instance.virtualUserId
                + (record.versionName.trim().isEmpty() ? "" : " · v" + record.versionName));
        String status = "状态：" + statusText(instance.lastRuntimeStatus);
        if (instance.lastRuntimeAt > 0) {
            status += " · " + DateFormat.getDateTimeInstance().format(new Date(instance.lastRuntimeAt));
        }
        ((TextView) view.findViewById(R.id.status)).setText(status);
        Button launch = view.findViewById(R.id.launch);
        launch.setEnabled(!record.launchActivity.trim().isEmpty()
                && NativeGuestExecutionPolicy.isRuntimeAllowed(record));
        launch.setOnClickListener(v -> listener.onLaunch(item));
        ((Button) view.findViewById(R.id.settings)).setOnClickListener(v -> listener.onSettings(item));
        ((Button) view.findViewById(R.id.clone)).setOnClickListener(v -> listener.onClone(item));
        ((Button) view.findViewById(R.id.clear)).setOnClickListener(v -> listener.onClear(item));
        ((Button) view.findViewById(R.id.delete)).setOnClickListener(v -> listener.onDelete(item));
        return view;
    }

    private Drawable icon(SandboxRecord record) {
        try {
            ApplicationInfo info = packageManager.getPackageArchiveInfo(record.apkPath,
                    PackageManager.GET_META_DATA) == null ? null
                    : packageManager.getPackageArchiveInfo(record.apkPath, PackageManager.GET_META_DATA).applicationInfo;
            if (info != null) {
                info.sourceDir = record.apkPath;
                info.publicSourceDir = record.apkPath;
                Drawable value = info.loadIcon(packageManager);
                if (value != null) return value;
            }
        } catch (Exception ignored) { }
        return packageManager.getDefaultActivityIcon();
    }

    private static String statusText(String value) {
        if (value == null || value.trim().isEmpty()) return "未测试";
        return value.replace("NOT_TESTED", "未测试").replace("READY", "已准备");
    }
}
