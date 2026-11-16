package app.recordkit.health;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import java.util.Calendar;

// 기상 알람(2026-11-16 룡 「잘게요 누르면 내가 일어날 시각 설정되게 하고 그때 알람」).
// 「잘게요」 때 고른 시각에 자명종처럼 울린다(폰 알람과 같은 setAlarmClock, 상단에 ⏰ 표시).
// 울리면 건강 기록 화면을 띄우고, 「일어났어요」(화면) 또는 알림 [끄기]를 누를 때까지 계속 울린다(10분 뒤 저절로 멈춤).
// 「일어났어요」를 알람보다 먼저 누르면 그날 알람은 지운다. 긴급 해제로는 지우지 않는다(다시 잘 수 있어서).
public class WakeAlarm extends BroadcastReceiver {
    static final String CH = "wake_alarm";
    static final int N_ALARM = 7203;

    static SharedPreferences p(Context c) { return Moments.p(c); }

    /** "06:30" → 다음에 오는 그 시각에 건다 */
    static void set(Context c, String hm) {
        String[] t = hm.split(":");
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
        at.set(Calendar.MINUTE, Integer.parseInt(t[1]));
        at.set(Calendar.SECOND, 0);
        at.set(Calendar.MILLISECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        p(c).edit().putLong("m_alarm_at", at.getTimeInMillis()).putString("m_alarm_hm", hm).apply();
        arm(c, at.getTimeInMillis());
    }

    private static void arm(Context c, long at) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent show = PendingIntent.getActivity(c, 7501, Moments.screenIntent(c), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try { am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), fire(c)); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire(c)); }
    }

    private static PendingIntent fire(Context c) {
        return PendingIntent.getBroadcast(c, 7502, new Intent(c, WakeAlarm.class).setAction("app.recordkit.health.wake"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** 다음 알람 지우기(일어났어요 · 알람 없이 잘게요) */
    static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(fire(c));
        p(c).edit().remove("m_alarm_at").apply();
    }

    /** 걸려 있는 알람 시각("06:30"), 없으면 "" */
    static String time(Context c) {
        return p(c).getLong("m_alarm_at", 0) > System.currentTimeMillis() ? p(c).getString("m_alarm_hm", "") : "";
    }

    /** 마지막으로 고른 시각(다음 「잘게요」 때 먼저 골라 둔다) */
    static String lastTime(Context c) { return p(c).getString("m_alarm_hm", ""); }

    /** 폰을 다시 켜면 알람이 사라져서 다시 건다 */
    static void rearm(Context c) {
        long at = p(c).getLong("m_alarm_at", 0);
        if (at > System.currentTimeMillis()) arm(c, at);
    }

    /** 울리는 소리 멈추기 */
    static void stop(Context c) {
        c.getSystemService(NotificationManager.class).cancel(N_ALARM);
        p(c).edit().putBoolean("m_ringing", false).apply();
    }

    static boolean ringing(Context c) { return p(c).getBoolean("m_ringing", false); }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context c = ctx.getApplicationContext();
        p(c).edit().remove("m_alarm_at").putBoolean("m_ringing", true).apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        NotificationChannel ch = new NotificationChannel(CH, "기상 알람", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 800, 600, 800});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
        Intent screen = Moments.screenIntent(c).putExtra("wake_alarm", true);
        PendingIntent full = PendingIntent.getActivity(c, 7503, screen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent off = PendingIntent.getBroadcast(c, 7504, new Intent(c, MomentAction.class).setAction("app.recordkit.health.alarm_off"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(Moments.icon(c))
                .setContentTitle("⏰ 일어날 시간이에요 " + p(c).getString("m_alarm_hm", ""))
                .setContentText("건강 기록에서 「일어났어요 ☀️」를 눌러 주세요")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(full, true)
                .setContentIntent(full)
                .setOngoing(true)
                .setTimeoutAfter(10 * 60 * 1000L)
                .addAction(new Notification.Action.Builder(null, "끄기", off).build())
                .build();
        n.flags |= Notification.FLAG_INSISTENT;
        try { nm.notify(N_ALARM, n); } catch (SecurityException ignored) {}
        // 화면을 바로 띄워 본다(잠금 중이면 접근성 서비스가 이미 이 앱을 앞에 두고 있다)
        try { c.startActivity(screen); } catch (Exception ignored) {}
    }
}
