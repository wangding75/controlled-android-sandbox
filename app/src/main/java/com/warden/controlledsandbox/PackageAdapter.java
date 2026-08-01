package com.warden.controlledsandbox;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class PackageAdapter extends BaseAdapter {
    interface Listener {
        void onPrepare(SandboxItem item);
        void onLaunch(SandboxItem item);
        void onComponentTest(SandboxItem item);
        void onClone(SandboxItem item);
        void onDelete(SandboxItem item);
    }
    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<SandboxItem> items = new ArrayList<>();
    PackageAdapter(Context context, Listener listener) { inflater = LayoutInflater.from(context); this.listener = listener; }
    void replace(List<SandboxItem> values) { items.clear(); items.addAll(values); notifyDataSetChanged(); }
    @Override public int getCount() { return items.size(); }
    @Override public SandboxItem getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView == null ? inflater.inflate(R.layout.row_package, parent, false) : convertView;
        SandboxItem item = getItem(position);
        SandboxRecord record = item.record;
        SandboxInstance instance = item.instance;
        ((TextView) view.findViewById(R.id.title)).setText(record.label + " · " + instance.displayName
                + (record.versionName.trim().isEmpty() ? "" : "  " + record.versionName));
        ((TextView) view.findViewById(R.id.summary)).setText(record.packageName + " · user=" + instance.virtualUserId
                + "\nEntry: " + empty(record.launchActivity) + "\nSHA-256: " + truncate(record.sha256));
        String status = "Runtime: " + instance.lastRuntimeStatus;
        if (instance.lastRuntimeAt > 0) status += " · " + DateFormat.getDateTimeInstance().format(new Date(instance.lastRuntimeAt));
        ((TextView) view.findViewById(R.id.status)).setText(status);
        Button prepare = view.findViewById(R.id.probe); prepare.setOnClickListener(v -> listener.onPrepare(item));
        Button launch = view.findViewById(R.id.launch);
        launch.setEnabled(!record.launchActivity.trim().isEmpty()
                && NativeGuestExecutionPolicy.isRuntimeAllowed(record));
        launch.setOnClickListener(v -> listener.onLaunch(item));
        Button components = view.findViewById(R.id.components); components.setOnClickListener(v -> listener.onComponentTest(item));
        Button clone = view.findViewById(R.id.clone); clone.setOnClickListener(v -> listener.onClone(item));
        Button delete = view.findViewById(R.id.delete); delete.setOnClickListener(v -> listener.onDelete(item));
        return view;
    }
    private static String empty(String value) { return value == null || value.trim().isEmpty() ? "<missing>" : value; }
    private static String truncate(String value) { return value == null || value.length() <= 20 ? value : value.substring(0, 20) + "…"; }
}
