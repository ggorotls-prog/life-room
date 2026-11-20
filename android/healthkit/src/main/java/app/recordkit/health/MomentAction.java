package app.recordkit.health;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

// 알림 버튼: 끼니 사진 [올리기]·[아니요], 운동 [적기]·[아니요], 기상 알람 [끄기]. 앱을 열지 않고 바로 저장한다.
// 폰을 다시 켜거나 앱이 업데이트되면 사진 지켜보기·운동 살피기·기상 알람을 다시 건다.
public class MomentAction extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context c = ctx.getApplicationContext();
        String a = intent.getAction() == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Moments.schedule(c);
            WakeAlarm.rearm(c);
            return;
        }
        int nid = intent.getIntExtra("nid", 0);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (a.endsWith(".alarm_off")) { WakeAlarm.stop(c); return; }
        if (nid != 0) nm.cancel(nid);
        if (a.endsWith(".meal_no") || a.endsWith(".ex_no")) return;
        String data = intent.getStringExtra("data"), slot = intent.getStringExtra("slot");
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (a.endsWith(".meal_up")) {
                    String name = "breakfast".equals(slot) ? "아침" : "lunch".equals(slot) ? "점심" : "저녁";
                    String photo;
                    try { photo = Moments.shrink(c, Uri.parse(data)); }
                    catch (Exception e) { Moments.info(c, "사진을 읽지 못했어요. 앱에서 올려 주세요", false); return; }
                    String err = Moments.post(c, "diet", photo, slot, null);
                    if (err == null) {
                        String meals = Moments.p(c).getString("m_meals", "");
                        if (Moments.today().equals(Moments.p(c).getString("m_meals_day", ""))) Moments.setMeals(c, Moments.today(), meals.isEmpty() ? slot : meals + "," + slot);
                        else Moments.setMeals(c, Moments.today(), slot);
                    }
                    Moments.info(c, err == null ? name + " 사진 올렸어요 🥗" : err, err == null);
                } else if (a.endsWith(".meal_text")) {
                    // 끼니 시간 알림에서 바로 적은 글(2026-11-20)
                    android.os.Bundle r = android.app.RemoteInput.getResultsFromIntent(intent);
                    CharSequence t = r == null ? null : r.getCharSequence("text");
                    String note = t == null ? "" : t.toString().trim();
                    if (note.isEmpty()) return;
                    if (note.length() > 60) note = note.substring(0, 60);
                    String name = "breakfast".equals(slot) ? "아침" : "lunch".equals(slot) ? "점심" : "저녁";
                    String err = Moments.post(c, "diet", null, slot, note);
                    if (err == null) {
                        String meals = Moments.p(c).getString("m_meals", "");
                        if (Moments.today().equals(Moments.p(c).getString("m_meals_day", ""))) Moments.setMeals(c, Moments.today(), meals.isEmpty() ? slot : meals + "," + slot);
                        else Moments.setMeals(c, Moments.today(), slot);
                    }
                    Moments.info(c, err == null ? name + " 「" + note + "」 적었어요 🥗" : err, err == null);
                } else if (a.endsWith(".ex_up")) {
                    String err = Moments.post(c, "exercise", null, null, data);
                    Moments.info(c, err == null ? "「" + data + "」 적었어요 💪" : err, err == null);
                }
            } finally { pr.finish(); }
        }).start();
    }
}
