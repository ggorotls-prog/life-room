package app.recordkit.health;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

// 건강 기록 아침 8시·밤 10시 알림 원본 한 벌(2026-11-15 「하나로 모으자」). 서버 없이 폰이 직접 울린다. 그 시각 언저리에 온다.
// 건강 기록 앱(Reminder)과 라이프 RPG(HealthReminder)가 이어받는다. 앱마다 다른 것: 누르면 열 화면, 알림 아이콘, 울리지 않을 때(skip).
public abstract class DayReminder extends BroadcastReceiver {
    private static final int MORNING = 8, NIGHT = 22;

    protected abstract Class<? extends Activity> screen();
    protected abstract int smallIcon();
    /** 라이프 RPG: 건강 기록 앱이 같이 깔려 있으면 그 앱이 울리므로 여기서는 안 울린다 */
    protected boolean skip(Context c) { return false; }
    /** 알림 갈래 이름(라이프 RPG는 자기 「daily」가 있어 다른 이름을 쓴다) */
    protected String channel() { return "daily"; }

    public static void schedule(Context c, Class<? extends DayReminder> cls) {
        set(c, cls, MORNING);
        set(c, cls, NIGHT);
    }

    private static void set(Context c, Class<? extends DayReminder> cls, int hour) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, hour);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(c, cls).putExtra("hour", hour);
        PendingIntent p = PendingIntent.getBroadcast(c, hour, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        c.getSystemService(AlarmManager.class).setInexactRepeating(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), AlarmManager.INTERVAL_DAY, p);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) { schedule(c, getClass()); return; }
        if (skip(c)) return;
        boolean morning = intent.getIntExtra("hour", MORNING) == MORNING;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(channel(), "아침·밤 기록 알림", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, screen()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        try {
            nm.notify(morning ? 1 : 2, new Notification.Builder(c, channel())
                    .setSmallIcon(smallIcon())
                    .setContentTitle(morning ? "좋은 아침이에요 ☀️" : "오늘 하루 수고하셨어요 🌙")
                    .setContentText(morning ? "일어난 시간을 적어 주세요" : "주무시기 전에 오늘을 마감해 주세요")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build());
        } catch (SecurityException ignored) {}
    }
}
