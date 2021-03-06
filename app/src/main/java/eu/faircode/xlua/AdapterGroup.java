/*
    This file is part of XPrivacy/Lua.

    XPrivacy/Lua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacy/Lua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacy/Lua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2018 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.content.Context;
import android.content.res.Resources;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.RecyclerView;
import android.text.Html;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdapterGroup extends RecyclerView.Adapter<AdapterGroup.ViewHolder> {
    private static final String TAG = "XLua.Group";

    private XApp app;
    private List<String> groups;
    private Map<String, List<XHook>> hooks;

    private ExecutorService executor = Executors.newCachedThreadPool();

    public class ViewHolder extends RecyclerView.ViewHolder
            implements CompoundButton.OnCheckedChangeListener, View.OnClickListener {
        String group;
        List<XHook> hooks;

        View itemView;
        ImageView ivException;
        ImageView ivInstalled;
        TextView tvUsed;
        TextView tvGroup;
        CheckBox cbAssigned;

        ViewHolder(View itemView) {
            super(itemView);

            this.itemView = itemView;
            ivException = itemView.findViewById(R.id.ivException);
            ivInstalled = itemView.findViewById(R.id.ivInstalled);
            tvUsed = itemView.findViewById(R.id.tvUsed);
            tvGroup = itemView.findViewById(R.id.tvGroup);
            cbAssigned = itemView.findViewById(R.id.cbAssigned);
        }

        private void wire() {
            ivException.setOnClickListener(this);
            tvGroup.setOnClickListener(this);
            cbAssigned.setOnCheckedChangeListener(this);
        }

        private void unwire() {
            ivException.setOnClickListener(null);
            tvGroup.setOnClickListener(null);
            cbAssigned.setOnCheckedChangeListener(null);
        }

        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.ivException:
                    StringBuilder sb = new StringBuilder();
                    for (XAssignment assignment : app.assignments)
                        if (assignment.hook.getGroup().equals(group))
                            if (assignment.exception != null) {
                                sb.append("<b>");
                                sb.append(Html.escapeHtml(assignment.hook.getId()));
                                sb.append("</b><br>");
                                sb.append(Html.escapeHtml(assignment.exception));
                                sb.append("<br>");
                            }

                    LayoutInflater inflater = LayoutInflater.from(view.getContext());
                    View alert = inflater.inflate(R.layout.exception, null, false);
                    TextView tvException = alert.findViewById(R.id.tvException);
                    tvException.setText(Html.fromHtml(sb.toString()));

                    new AlertDialog.Builder(view.getContext())
                            .setView(alert)
                            .create()
                            .show();
                    break;

                case R.id.tvGroup:
                    cbAssigned.setChecked(!cbAssigned.isChecked());
                    break;
            }
        }

        @Override
        public void onCheckedChanged(CompoundButton compoundButton, final boolean checked) {
            switch (compoundButton.getId()) {
                case R.id.cbAssigned:
                    for (XHook hook : hooks)
                        app.assignments.remove(new XAssignment(hook));
                    if (checked)
                        for (XHook hook : hooks)
                            app.assignments.add(new XAssignment(hook));
                    app.notifyChanged();

                    executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            List<String> hookids = new ArrayList<>();
                            for (XHook hook : hooks)
                                hookids.add(hook.getId());
                            try {
                                XService.getClient().assignHooks(
                                        hookids, app.packageName, app.uid, !checked, !app.persistent);
                            } catch (Throwable ex) {
                                Log.e(TAG, Log.getStackTraceString(ex));
                                Snackbar.make(itemView, ex.toString(), Snackbar.LENGTH_LONG).show();
                            }
                        }
                    });
                    break;
            }
        }
    }

    AdapterGroup() {
        setHasStableIds(false);
    }

    void set(XApp app, List<XHook> hooks) {
        this.app = app;
        this.groups = new ArrayList<>();
        this.hooks = new HashMap<>();

        for (XHook hook : hooks) {
            if (!this.groups.contains(hook.getGroup())) {
                this.groups.add(hook.getGroup());
                this.hooks.put(hook.getGroup(), new ArrayList<XHook>());
            }
            this.hooks.get(hook.getGroup()).add(hook);
        }

        final Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.SECONDARY); // Case insensitive, process accents etc

        Collections.sort(this.groups, new Comparator<String>() {
            @Override
            public int compare(String group1, String group2) {
                return collator.compare(group1, group2);
            }
        });

        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return -1;
    }

    @Override
    public int getItemCount() {
        return groups.size();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.group, parent, false));
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        holder.unwire();
        holder.group = groups.get(position);
        holder.hooks = hooks.get(holder.group);

        boolean exception = false;
        boolean installed = true;
        long used = -1;
        boolean assigned = false;
        for (XAssignment assignment : app.assignments)
            if (assignment.hook.getGroup().equals(holder.group)) {
                if (assignment.exception != null)
                    exception = true;
                if (assignment.installed < 0)
                    installed = false;
                if (assignment.restricted)
                    used = Math.max(used, assignment.used);
                assigned = true;
            }

        Context context = holder.itemView.getContext();
        Resources resources = holder.itemView.getContext().getResources();
        String name = holder.group.toLowerCase().replaceAll("[^a-z]", "_");
        int resId = resources.getIdentifier("group_" + name, "string", context.getPackageName());
        if (resId > 0)
            name = resources.getString(resId);

        holder.ivException.setVisibility(exception && assigned ? View.VISIBLE : View.GONE);
        holder.ivInstalled.setVisibility(installed && assigned ? View.VISIBLE : View.GONE);
        holder.tvUsed.setVisibility(used < 0 ? View.GONE : View.VISIBLE);
        holder.tvUsed.setText(used < 0 ? "" : DateUtils.formatDateTime(context, used,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_ALL));
        holder.tvGroup.setText(name);
        holder.cbAssigned.setChecked(assigned);

        holder.wire();
    }
}
