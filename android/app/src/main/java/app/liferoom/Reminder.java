package app.liferoom;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

// 하루 두 번 알림. 서버 없이 폰이 직접 울린다. 정확한 시각이 아니라 그 시각 언저리에 온다.
public class Reminder extends BroadcastReceiver {
    private static final int MORNING = 8, NIGHT = 22;
    private static final String CHANNEL = "daily";

    static void schedule(Context c) {
        set(c, MORNING);
        set(c, NIGHT);
    }

    private static void set(Context c, int hour) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(c, Reminder.class).putExtra("hour", hour);
        PendingIntent p = PendingIntent.getBroadcast(c, hour, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(AlarmManager.class).setInexactRepeating(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), AlarmManager.INTERVAL_DAY, p);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) { schedule(c); return; }
        boolean morning = intent.getIntExtra("hour", MORNING) == MORNING;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "아침·밤 기록 알림", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(morning ? 1 : 2, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(morning ? "좋은 아침이에요 ☀️" : "오늘 하루 수고하셨어요 🌙")
                .setContentText(morning ? "일어난 시간을 적어 주세요" : "주무시기 전에 오늘을 마감해 주세요")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
