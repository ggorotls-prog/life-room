package app.liferoom;

import android.content.Context;

// 하루 두 번 알림(아침 8시·밤 10시). 내용은 원본 한 벌 healthkit의 DayReminder(라이프 RPG와 같이 씀).
public class Reminder extends app.recordkit.health.DayReminder {
    static void schedule(Context c) { schedule(c, Reminder.class); }

    @Override
    protected Class<? extends android.app.Activity> screen() { return MainActivity.class; }

    @Override
    protected int smallIcon() { return R.drawable.ic_notify; }
}