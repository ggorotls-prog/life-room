package app.recordkit.health;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

// 「그 순간에 앱이 먼저 묻기」 원본 한 벌(2026-11-16 룡: 깔고도 매일 안 적게 되는 문제 → 통화 뒤 「연락처에 추가할까요?」처럼).
// 건강 기록 앱과 라이프 RPG 🏠 집이 같이 쓴다(이 모듈 manifest에 등록해서 두 앱에 저절로 들어간다).
// ① 끼니 사진: 끼니 시간 안에 폰 카메라로 찍으면 소리 없는 알림 「점심 사진인가요? [올리기] [아니요]」. 끼니마다 한 번만, 적은 끼니는 안 묻는다. 간식은 안 묻는다(룡 「양심에 맡기고」).
// ② 운동: 삼성 헬스 → Health Connect에 새 운동이 들어오면 「걷기 20분, 운동으로 적을까요? [적기]」. 저절로 저장하지 않는다(룡 「자동으로 하게 하는 것은 하지 말고」).
// 버튼을 누르면 앱을 열지 않고 바로 서버(life_post)에 저장한다. 로그인 열쇠는 화면(사이트)이 setToken으로 넘겨 준다.
// 두 앱에서 다 켜면 두 번 묻는다 → 사이트 안내에 「한 곳에서만 켜기」.
public final class Moments {
    static final String SB = "https://trlnatdkebjvgiqneulv.supabase.co";
    static final String KEY = "sb_publishable_BqE1VCDg50oi9zCflCjMuw_wYF55CTj"; // 사이트에 공개된 키와 같음
    static final String CH_MEAL = "moment_meal", CH_EX = "moment_ex", CH_INFO = "moment_info", CH_FEED = "moment_feed";
    static final int JOB_PHOTO = 7101, JOB_EX = 7102, JOB_FEED = 7103;
    static final int N_MEAL = 7201, N_EX = 7202, N_INFO = 7204, N_FEED = 7205;
    static final String[] SLOTS = {"breakfast", "lunch", "dinner"};
    static final String[] SLOT_NAMES = {"아침", "점심", "저녁"};
    static final String DEFAULT_TIMES = "6-10,11-14,17-21"; // 룡 OK 2026-11-16, 화면에서 바꿀 수 있다

    private Moments() {}

    static SharedPreferences p(Context c) { return c.getSharedPreferences(SleepLock.PREFS, Context.MODE_PRIVATE); }

    /** 하루 경계 새벽 4시(DB life_today와 같음) */
    static String today() {
        Calendar k = Calendar.getInstance();
        k.add(Calendar.HOUR_OF_DAY, -4);
        return String.format(Locale.US, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
    }

    // ---------- 화면이 알려 주는 것 ----------
    static void setToken(Context c, String token) {
        boolean first = p(c).getString("m_token", null) == null;
        p(c).edit().putString("m_token", token).apply();
        if (first) schedule(c); // 처음 로그인하면 조 소식 알림을 건다
    }

    static void setMeals(Context c, String day, String csv) { p(c).edit().putString("m_meals_day", day).putString("m_meals", csv == null ? "" : csv).apply(); }

    static boolean mealDone(Context c, String slot) {
        SharedPreferences s = p(c);
        if (!today().equals(s.getString("m_meals_day", ""))) return false;
        for (String x : s.getString("m_meals", "").split(",")) if (x.equals(slot)) return true;
        return false;
    }

    static String times(Context c) { return p(c).getString("m_times", DEFAULT_TIMES); }

    static void setTimes(Context c, String v) {
        if (v == null || !v.matches("\\d{1,2}-\\d{1,2},\\d{1,2}-\\d{1,2},\\d{1,2}-\\d{1,2}")) return;
        p(c).edit().putString("m_times", v).apply();
    }

    /** 지금이 어느 끼니 시간인가(없으면 -1) */
    static int slotNow(Context c) {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String[] parts = times(c).split(",");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String[] ab = parts[i].split("-");
            int a = Integer.parseInt(ab[0]), b = Integer.parseInt(ab[1]);
            if (h >= a && h < b) return i;
        }
        return -1;
    }

    // ---------- 켜기·끄기 ----------
    static String photoPerm() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE; }

    /** 이 판(매니페스트)에 그 권한이 들어 있나. 구글 플레이용 판은 사진·운동 읽기·배터리 직접 요청 권한을 뺐다(2026-11-19) */
    static boolean declared(Context c, String perm) {
        try {
            String[] ps = c.getPackageManager().getPackageInfo(c.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (ps != null) for (String p : ps) if (perm.equals(p)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    static boolean photoAllowed(Context c) { return c.checkSelfPermission(photoPerm()) == PackageManager.PERMISSION_GRANTED; }

    static boolean photoOn(Context c) { return p(c).getBoolean("m_photo", false); }

    static void setPhotoOn(Context c, boolean on) {
        SharedPreferences.Editor e = p(c).edit().putBoolean("m_photo", on);
        if (on) e.putLong("m_photo_last", System.currentTimeMillis() / 1000); // 켠 뒤 찍은 사진만
        e.apply();
        schedule(c);
    }

    static boolean exOn(Context c) { return p(c).getBoolean("m_ex", false); }

    static void setExOn(Context c, boolean on) {
        SharedPreferences.Editor e = p(c).edit().putBoolean("m_ex", on);
        if (on && p(c).getLong("m_ex_last", 0) == 0) e.putLong("m_ex_last", System.currentTimeMillis() - 30 * 60 * 1000L); // 켜기 30분 전 운동부터
        e.apply();
        schedule(c);
    }

    /** 사진 지켜보기·운동 살피기 작업을 건다(켜진 것만). 앱을 열 때·켤 때·폰을 다시 켤 때 */
    public static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName job = new ComponentName(c, MomentJob.class);
        if (photoOn(c) && photoAllowed(c)) {
            if (js.getPendingJob(JOB_PHOTO) == null) schedulePhoto(c);
        } else js.cancel(JOB_PHOTO);
        if (exOn(c)) {
            if (js.getPendingJob(JOB_EX) == null) {
                try {
                    js.schedule(new JobInfo.Builder(JOB_EX, job)
                            .setPeriodic(15 * 60 * 1000L)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .build());
                } catch (Exception ignored) {}
            }
        } else js.cancel(JOB_EX);
        // 조 소식(2026-11-20): 조원이 올리면·응원이 오면 알림. 로그인돼 있으면 15분마다 서버를 본다
        if (p(c).getString("m_token", null) != null && !feedTwin(c)) {
            if (js.getPendingJob(JOB_FEED) == null) {
                try {
                    js.schedule(new JobInfo.Builder(JOB_FEED, job)
                            .setPeriodic(15 * 60 * 1000L)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .build());
                } catch (Exception ignored) {}
            }
        } else js.cancel(JOB_FEED);
    }

    /** 건강 기록 앱인데 라이프 RPG도 깔려 있으면 조 소식은 라이프 RPG가 알린다(두 번 알리지 않게) */
    static boolean feedTwin(Context c) {
        if (!c.getPackageName().startsWith("app.liferoom")) return false;
        try { c.getPackageManager().getPackageInfo("app.liferpg", 0); return true; } catch (Exception e) { return false; }
    }

    // ---------- 조 소식 ----------
    /** 서버 life_feed: 지난번 뒤에 조원이 올린 것과 내 기록에 온 응원을 알림 하나로 모아 보인다. 자는 중에는 알리지 않고 아침에 모아서 알린다 */
    static void checkFeed(Context c) {
        SharedPreferences s = p(c);
        String token = s.getString("m_token", null);
        if (token == null || feedTwin(c) || SleepLock.isLocked(c)) return;
        try {
            String since = s.getString("m_feed_since", null);
            JSONObject body = new JSONObject().put("p_token", token).put("p_since", since == null ? JSONObject.NULL : since);
            String res = call("life_feed", body);
            if (res == null) return;
            JSONObject d = new JSONObject(res);
            s.edit().putString("m_feed_since", d.optString("now", since)).apply();
            if (since == null) return; // 처음에는 기준 시각만 잡는다(깔자마자 지난 것을 쏟지 않는다)
            JSONArray posts = d.optJSONArray("posts"), cheers = d.optJSONArray("cheers");
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            for (int i = 0; cheers != null && i < cheers.length(); i++) {
                JSONObject x = cheers.getJSONObject(i);
                lines.add("👏 " + x.optString("nickname") + "님이 " + what(x) + " 기록을 응원했어요");
            }
            for (int i = 0; posts != null && i < posts.length(); i++) {
                JSONObject x = posts.getJSONObject(i);
                String note = x.isNull("note") ? "" : x.optString("note");
                lines.add(x.optString("nickname") + "님이 " + what(x) + (note.isEmpty() ? "" : "(" + note + ")") + "을 올렸어요");
            }
            if (lines.isEmpty()) return;
            String title = lines.size() == 1 ? lines.get(0) : "우리 조 소식 " + lines.size() + "개";
            Notification.Builder b = new Notification.Builder(c, channel(c, CH_FEED))
                    .setSmallIcon(icon(c))
                    .setContentTitle(title)
                    .setContentIntent(openApp(c, 5))
                    .setAutoCancel(true);
            if (lines.size() > 1) {
                Notification.InboxStyle st = new Notification.InboxStyle();
                for (int i = 0; i < Math.min(lines.size(), 6); i++) st.addLine(lines.get(i));
                b.setContentText(lines.get(lines.size() - 1)).setStyle(st);
            } else b.setContentText("눌러서 응원해 주세요");
            notify(c, N_FEED, b.build());
        } catch (Exception ignored) {}
    }

    /** "점심 식단" · "운동" */
    static String what(JSONObject x) {
        if (!"diet".equals(x.optString("kind"))) return "운동";
        String slot = x.optString("slot");
        String n = "breakfast".equals(slot) ? "아침 " : "lunch".equals(slot) ? "점심 " : "dinner".equals(slot) ? "저녁 " : "snack".equals(slot) ? "간식 " : "";
        return n + "식단";
    }

    /** 서버 함수 부르기. 돌려주는 것: 응답 글, 안 되면 null */
    static String call(String fn, JSONObject body) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(SB + "/rest/v1/rpc/" + fn).openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("apikey", KEY);
            h.setRequestProperty("Authorization", "Bearer " + KEY);
            h.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = h.getResponseCode();
            String res = "";
            try (InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream()) {
                if (in != null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    res = buf.toString("UTF-8");
                }
            }
            h.disconnect();
            return code < 400 ? res : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 새 사진이 들어오면 한 번 깨운다(한 번 쓰면 다시 건다) */
    static void schedulePhoto(Context c) {
        try {
            c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(JOB_PHOTO, new ComponentName(c, MomentJob.class))
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(1000)
                    .setTriggerContentMaxDelay(5000)
                    .build());
        } catch (Exception ignored) {}
    }

    // ---------- ① 끼니 사진 ----------
    static void checkPhotos(Context c) {
        if (!photoOn(c) || !photoAllowed(c) || p(c).getString("m_token", null) == null) return;
        SharedPreferences s = p(c);
        long last = s.getLong("m_photo_last", System.currentTimeMillis() / 1000);
        long newest = last;
        Uri found = null;
        String[] proj = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH};
        try (Cursor cur = c.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Images.Media.DATE_ADDED + " > ?", new String[]{String.valueOf(last)},
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                long added = cur.getLong(1);
                if (added > newest) newest = added;
                String path = cur.getString(2);
                // 폰 카메라로 찍은 사진만(캡처·받은 사진 빼기). 삼성·구글 카메라 모두 DCIM/Camera
                if (found == null && path != null && path.startsWith("DCIM/Camera")
                        && added > System.currentTimeMillis() / 1000 - 15 * 60) {
                    found = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0));
                }
            }
        } catch (Exception e) { return; }
        s.edit().putLong("m_photo_last", newest).apply();
        if (found == null) return;
        int i = slotNow(c);
        if (i < 0) return;
        String slot = SLOTS[i], day = today();
        if (mealDone(c, slot) || s.getBoolean("m_asked_" + day + "_" + slot, false)) return;
        s.edit().putBoolean("m_asked_" + day + "_" + slot, true).apply();

        Notification.Builder b = new Notification.Builder(c, channel(c, CH_MEAL))
                .setSmallIcon(icon(c))
                .setContentTitle(SLOT_NAMES[i] + " 사진인가요? 🥗")
                .setContentText("「올리기」를 누르면 " + SLOT_NAMES[i] + "으로 올라가요")
                .setContentIntent(openApp(c, 1))
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "올리기", action(c, "meal_up", found.toString(), slot, 11, N_MEAL)).build())
                .addAction(new Notification.Action.Builder(null, "아니요", action(c, "meal_no", null, slot, 12, N_MEAL)).build());
        try {
            Bitmap thumb = c.getContentResolver().loadThumbnail(found, new Size(512, 512), null);
            b.setLargeIcon(thumb).setStyle(new Notification.BigPictureStyle().bigPicture(thumb).bigLargeIcon((Bitmap) null));
        } catch (Exception ignored) {}
        notify(c, N_MEAL, b.build());
    }

    /** 사진을 사이트와 같은 꼴로 줄인다(긴 변 800, jpeg, 39만 자 아래). 사진 위치 정보는 같이 사라진다 */
    static String shrink(Context c, Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 800) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o2); }
        if (bm == null) throw new Exception("photo");
        int rot = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {}
        int size = 800, q = 70;
        for (int t = 0; t < 5; t++) {
            float k = Math.min(1f, size / (float) Math.max(bm.getWidth(), bm.getHeight()));
            Matrix m = new Matrix();
            m.postScale(k, k);
            if (rot != 0) m.postRotate(rot);
            Bitmap out = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, q, buf);
            String data = "data:image/jpeg;base64," + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
            if (data.length() < 390000) return data;
            size = (int) (size * .8f);
            q -= 10;
        }
        throw new Exception("photo");
    }

    // ---------- ② 운동 ----------
    static void checkExercise(Context c) {
        if (!exOn(c) || p(c).getString("m_token", null) == null) return;
        if (!HcReader.available(c) || !HcReader.granted(c)) return;
        SharedPreferences s = p(c);
        long since = Math.max(s.getLong("m_ex_last", 0), System.currentTimeMillis() - 12 * 60 * 60 * 1000L);
        java.util.List<String[]> list;
        try { list = HcReader.sessionsSince(c, since); } catch (Exception e) { return; }
        long newest = since;
        for (String[] r : list) {
            long end = Long.parseLong(r[0]);
            if (end > newest) newest = end;
            int min = Integer.parseInt(r[2]);
            if (min < 5) continue; // 너무 짧은 것은 안 묻는다
            String note = r[1] + " " + min + "분";
            int nid = N_EX + (int) (end % 1000) * 10;
            Notification n = new Notification.Builder(c, channel(c, CH_EX))
                    .setSmallIcon(icon(c))
                    .setContentTitle(note + ", 운동으로 적을까요? 🏃")
                    .setContentText("「적기」를 누르면 오늘 운동에 「" + note + "」으로 올라가요")
                    .setContentIntent(openApp(c, 2))
                    .setAutoCancel(true)
                    .addAction(new Notification.Action.Builder(null, "적기", action(c, "ex_up", note, null, 2000 + (int) (end % 1000) * 2, nid)).build())
                    .addAction(new Notification.Action.Builder(null, "아니요", action(c, "ex_no", null, null, 2001 + (int) (end % 1000) * 2, nid)).build())
                    .build();
            notify(c, nid, n);
        }
        s.edit().putLong("m_ex_last", newest).apply();
    }

    // ---------- 서버 ----------
    /** life_post. 돌려주는 것: null = 저장됨, 아니면 사람에게 보일 말 */
    static String post(Context c, String kind, String photo, String slot, String note) {
        String token = p(c).getString("m_token", null);
        if (token == null) return "앱에서 다시 로그인해 주세요";
        try {
            JSONObject body = new JSONObject()
                    .put("p_token", token).put("p_kind", kind)
                    .put("p_photo", photo == null ? JSONObject.NULL : photo)
                    .put("p_slot", slot == null ? JSONObject.NULL : slot)
                    .put("p_note", note == null ? JSONObject.NULL : note);
            HttpURLConnection h = (HttpURLConnection) new URL(SB + "/rest/v1/rpc/life_post").openConnection();
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("apikey", KEY);
            h.setRequestProperty("Authorization", "Bearer " + KEY);
            h.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = h.getResponseCode();
            String res = "";
            try (InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream()) {
                if (in != null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    res = buf.toString("UTF-8");
                }
            }
            h.disconnect();
            if (code < 400) return null;
            if (res.contains("need_wake") || res.contains("need_bed")) return "먼저 앱에서 「일어났어요」를 눌러 주세요";
            if (res.contains("\"auth\"")) return "앱에서 다시 로그인해 주세요";
            return "저장하지 못했어요. 앱에서 올려 주세요";
        } catch (Exception e) {
            return "인터넷이 안 돼서 못 올렸어요. 앱에서 올려 주세요";
        }
    }

    // ---------- 알림 도우미 ----------
    static String channel(Context c, String id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (id.equals(CH_MEAL)) nm.createNotificationChannel(new NotificationChannel(id, "끼니 사진 묻기(소리 없음)", NotificationManager.IMPORTANCE_LOW));
        else if (id.equals(CH_EX)) nm.createNotificationChannel(new NotificationChannel(id, "운동 묻기", NotificationManager.IMPORTANCE_DEFAULT));
        else if (id.equals(CH_FEED)) nm.createNotificationChannel(new NotificationChannel(id, "우리 조 소식·응원", NotificationManager.IMPORTANCE_DEFAULT));
        else nm.createNotificationChannel(new NotificationChannel(id, "올린 결과", NotificationManager.IMPORTANCE_LOW));
        return id;
    }

    static void notify(Context c, int id, Notification n) {
        try { c.getSystemService(NotificationManager.class).notify(id, n); } catch (SecurityException ignored) {}
    }

    static void info(Context c, String text, boolean ok) {
        Notification.Builder b = new Notification.Builder(c, channel(c, CH_INFO))
                .setSmallIcon(icon(c))
                .setContentTitle(text)
                .setContentIntent(openApp(c, 3))
                .setAutoCancel(true);
        if (ok) b.setTimeoutAfter(8000);
        notify(c, N_INFO, b.build());
    }

    /** 알림 작은 아이콘: 앱 아이콘을 쓴다(앱마다 ic_notify가 있으면 그것) */
    static int icon(Context c) {
        int id = c.getResources().getIdentifier("ic_notify", "drawable", c.getPackageName());
        return id != 0 ? id : c.getApplicationInfo().icon;
    }

    /** 알림을 누르면 이 앱의 건강 기록 화면(HealthScreen이 열릴 때 자기 이름을 적어 둔다) */
    static PendingIntent openApp(Context c, int req) {
        Intent i = screenIntent(c);
        return PendingIntent.getActivity(c, 7300 + req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Intent screenIntent(Context c) {
        String cls = p(c).getString("m_screen", null);
        Intent i;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Activity> k = cls == null ? null : (Class<? extends Activity>) Class.forName(cls);
            i = k != null ? new Intent(c, k) : c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        } catch (Exception e) {
            i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        }
        if (i == null) i = new Intent();
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static PendingIntent action(Context c, String what, String data, String slot, int req, int nid) {
        Intent i = new Intent(c, MomentAction.class).setAction("app.recordkit.health." + what)
                .putExtra("data", data).putExtra("slot", slot).putExtra("day", today()).putExtra("nid", nid);
        return PendingIntent.getBroadcast(c, 7400 + req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
