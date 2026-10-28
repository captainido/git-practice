package com.catgame.home;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Fires when the cat is due to get hungry and posts a notification. */
public class HungryReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "hungry";
    private static final int NOTIF_ID = 7;

    static void schedule(Context ctx, int seconds, String name) {
        Intent i = new Intent(ctx, HungryReceiver.class).putExtra("name", name);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + seconds * 1000L, pi);
    }

    static void clearNotification(Context ctx) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (MainActivity.inForeground) return;
        String name = intent.getStringExtra("name");
        if (name == null) name = "החתול";
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "החתול רעב", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx);
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 1, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("😿 " + name + " רעב!")
                .setContentText("מיאו! בוא תאכיל אותי 🍗")
                .setContentIntent(open)
                .setAutoCancel(true);
        nm.notify(NOTIF_ID, b.build());
    }
}
