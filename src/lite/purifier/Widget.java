package lite.purifier;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: PM2.5, status, power toggle. Talks to the Xiaomi cloud directly. */
public class Widget extends AppWidgetProvider {
    static final String TOGGLE = "lite.purifier.TOGGLE", REFRESH = "lite.purifier.REFRESH";

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { FilterCheck.schedule(c); update(c, false); }

    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (TOGGLE.equals(i.getAction())) update(c, true);
        else if (REFRESH.equals(i.getAction())) update(c, false);
    }

    /** Ask any placed widgets to reload (called by the app after you change something). */
    static void poke(Context c) { c.sendBroadcast(new Intent(REFRESH).setClass(c, Widget.class)); }

    void update(Context c, boolean toggle) {
        PendingResult done = goAsync();
        new Thread(() -> {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget);
            Cloud cloud = new Cloud(c);
            try {
                if (!cloud.loggedIn()) throw new IllegalStateException("Open the app to log in");
                String did = cloud.sp.getString("did", null);
                if (did == null) { cloud.devices(); did = cloud.sp.getString("did", null); }
                if (did == null) throw new IllegalStateException("No purifier found");
                Object[] v = cloud.getProps(did);
                boolean on = Boolean.TRUE.equals(v[Cloud.ON]);
                if (toggle) { on = !on; cloud.setRaw(did, Cloud.PROPS[Cloud.ON], on); }

                int pm = Cloud.num(v[Cloud.PM]);
                String q = Cloud.pmLabel(pm);
                int color = Cloud.pmColor(pm);
                String mode = new String[]{"Auto", "Sleep", "Favorite"}[Math.max(0, Math.min(2, Cloud.num(v[Cloud.MODE])))];
                String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(new java.util.Date());
                rv.setTextViewText(R.id.pm, String.valueOf(v[Cloud.PM]));
                rv.setTextColor(R.id.pm, color);
                rv.setTextViewText(R.id.quality, "PM2.5 · " + q);
                String filter = FilterCheck.level(Cloud.num(v[Cloud.FILTER])) > 0 && v[Cloud.FILTER] instanceof Number ? "⚠ Filter " + v[Cloud.FILTER] + "% · " : "";
                rv.setTextViewText(R.id.status, filter + (on ? mode : "Off") + " · " + v[Cloud.HUM] + "% · " + v[Cloud.TEMP] + "°C · " + time);
                rv.setTextViewText(R.id.power, on ? "ON" : "OFF");
                rv.setTextColor(R.id.power, on ? 0xFF0E1215 : 0xFFECF0F3);
                rv.setInt(R.id.power, "setBackgroundResource", on ? R.drawable.btn_on : R.drawable.btn_off);
            } catch (Exception e) {
                android.util.Log.w("Purifier", "widget", e);
                rv.setTextViewText(R.id.status, e instanceof IllegalStateException ? e.getMessage() : "Couldn't update · tap ↻");
            }
            int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            rv.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), f));
            rv.setOnClickPendingIntent(R.id.power, PendingIntent.getBroadcast(c, 1, new Intent(TOGGLE).setClass(c, Widget.class), f));
            rv.setOnClickPendingIntent(R.id.refresh, PendingIntent.getBroadcast(c, 2, new Intent(REFRESH).setClass(c, Widget.class), f));
            AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, Widget.class), rv);
            done.finish();
        }).start();
    }
}
