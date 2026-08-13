package com.warden.controlledsandbox;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** XH-aligned installed-app picker. It exposes host metadata and the import state together. */
final class InstalledAppAdapter extends BaseAdapter {
    interface Listener { void onAdd(InstalledApplication application); }

    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<InstalledApplication> items = new ArrayList<>();

    InstalledAppAdapter(android.content.Context context, Listener listener) {
        inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    void replace(List<InstalledApplication> values) {
        items.clear();
        if (values != null) items.addAll(values);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public InstalledApplication getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView == null
                ? inflater.inflate(R.layout.row_installed_app, parent, false) : convertView;
        InstalledApplication item = getItem(position);
        ((ImageView) view.findViewById(R.id.installedAppIcon)).setImageDrawable(item.icon);
        ((TextView) view.findViewById(R.id.installedAppTitle)).setText(item.label);
        String revision = item.versionName.trim().isEmpty()
                ? "versionCode " + item.versionCode : "v" + item.versionName;
        String splits = item.splitSourceDirs.isEmpty()
                ? "base APK" : "base APK + " + item.splitSourceDirs.size() + " split APK";
        ((TextView) view.findViewById(R.id.installedAppSummary)).setText(
                item.packageName + " · " + revision + " · " + splits
                        + (item.sandboxInstanceCount == 0
                        ? "\n" + "Not imported"
                        : "\n" + item.sandboxInstanceCount + " sandbox instance(s)"));
        Button action = view.findViewById(R.id.installedAppAction);
        action.setText(item.sandboxInstanceCount == 0
                ? R.string.action_add_installed : R.string.action_add_instance);
        action.setOnClickListener(v -> listener.onAdd(item));
        return view;
    }
}
