package es.canteramadrid.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Revisa en segundo plano los grupos de los equipos que sigues y avisa de:
 *  - resultado nuevo de un partido de tu equipo,
 *  - próximo partido asignado (o cambio de fecha, hora o campo),
 * y programa el aviso previo (por defecto 3 h antes) con el campo y la dirección.
 *
 * Preferencias ("avisos"):
 *  equipos  = [{"id","g","n"}]   lo guarda la página al seguir o dejar de seguir
 *  ajustes  = {"resultado":true,"asignado":true,"previo":true,"horas":3}
 *  e_<clave> = firma del partido la última vez que se vio
 */
public class Avisos extends Worker {

    static final String PREFS = "avisos";
    static final String DATOS = "https://raw.githubusercontent.com/alexample/cantera-madrid/main/data/";
    static final String CANAL_RESULTADO = "resultados";
    static final String CANAL_ASIGNADO = "partido_asignado";
    static final String CANAL_PREVIO = "antes_del_partido";
    private static final String TRABAJO = "revisar-equipos";
    static final TimeZone MADRID = TimeZone.getTimeZone("Europe/Madrid");

    public Avisos(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    /** Revisión periódica (Android puede retrasarla un poco para ahorrar batería). */
    static void programar(Context c) {
        Constraints red = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(Avisos.class, 30, TimeUnit.MINUTES)
                .setConstraints(red).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    /** Revisión inmediata (al abrir la app o al cambiar de equipos). */
    static void revisarYa(Context c) {
        Constraints red = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(Avisos.class).setConstraints(red).build();
        WorkManager.getInstance(c).enqueueUniqueWork(TRABAJO + "-ya", ExistingWorkPolicy.REPLACE, req);
    }

    static void crearCanales(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel r = new NotificationChannel(CANAL_RESULTADO, "Resultados", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("Cuando la federación publica el resultado de un partido de tus equipos.");
        NotificationChannel a = new NotificationChannel(CANAL_ASIGNADO, "Próximo partido asignado", NotificationManager.IMPORTANCE_DEFAULT);
        a.setDescription("Cuando se fija (o cambia) la fecha, la hora o el campo del próximo partido.");
        NotificationChannel p = new NotificationChannel(CANAL_PREVIO, "Antes del partido", NotificationManager.IMPORTANCE_HIGH);
        p.setDescription("Aviso unas horas antes del partido, con el campo y cómo llegar.");
        nm.createNotificationChannel(r);
        nm.createNotificationChannel(a);
        nm.createNotificationChannel(p);
    }

    static boolean puedeNotificar(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        crearCanales(c);
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray equipos;
        JSONObject aj;
        try { equipos = new JSONArray(p.getString("equipos", "[]")); } catch (Exception e) { equipos = new JSONArray(); }
        try { aj = new JSONObject(p.getString("ajustes", "{}")); } catch (Exception e) { aj = new JSONObject(); }
        boolean avResultado = aj.optBoolean("resultado", true);
        boolean avAsignado = aj.optBoolean("asignado", true);
        boolean avPrevio = aj.optBoolean("previo", true);
        int horas = aj.optInt("horas", 3);
        if (horas < 1 || horas > 12) horas = 3;

        JSONObject campos = camposCache(c, p);
        SharedPreferences.Editor ed = p.edit();
        Set<String> alarmasAntes = new HashSet<>(p.getStringSet("alarmas", new HashSet<>()));
        Set<String> alarmas = new HashSet<>();
        long ahora = System.currentTimeMillis();
        boolean fallo = false;

        // Un grupo puede tener varios equipos seguidos: se descarga una vez.
        JSONObject cacheGrupos = new JSONObject();
        for (int i = 0; i < equipos.length(); i++) {
            JSONObject eq = equipos.optJSONObject(i);
            if (eq == null) continue;
            String id = eq.optString("id"), gid = eq.optString("g"), nombre = eq.optString("n");
            if (id.isEmpty() || gid.isEmpty()) continue;
            JSONObject grupo = cacheGrupos.optJSONObject(gid);
            if (grupo == null) {
                try { grupo = new JSONObject(descargar(DATOS + "g/" + gid + ".json?t=" + (ahora / 60000))); }
                catch (Exception e) { fallo = true; continue; }
                try { cacheGrupos.put(gid, grupo); } catch (Exception ignored) { }
            }
            JSONArray part = grupo.optJSONArray("part");
            if (part == null) continue;
            boolean primeraVez = !p.getBoolean("ini_" + id, false);

            // Próximo partido (el primero sin resultado con fecha >= hoy, o el primero sin fecha)
            JSONArray proximo = null;
            long proximoInicio = Long.MAX_VALUE;
            for (int k = 0; k < part.length(); k++) {
                JSONArray m = part.optJSONArray(k);
                if (m == null) continue;
                String local = m.optString(3), visit = m.optString(5);
                if (!id.equals(local) && !id.equals(visit)) continue;
                boolean jugado = !m.isNull(7) && !m.isNull(8);
                String clave = "e_" + id + "_" + m.optInt(0) + "_" + local + "_" + visit;
                String firma = (jugado ? "R" + m.optInt(7) + "-" + m.optInt(8) : "P") + "|" + m.optString(1) + "|" + m.optString(2) + "|" + m.optString(9);
                String antes = p.getString(clave, null);
                long inicio = inicio(m.optString(1), m.optString(2));

                if (!primeraVez && antes != null && !antes.equals(firma)) {
                    if (jugado && antes.startsWith("P") && avResultado && inicio > ahora - 4L * 86400000L) {
                        notificarResultado(c, id, nombre, m);
                    } else if (!jugado && avAsignado && inicio > ahora && !m.optString(1).isEmpty()) {
                        String[] a = antes.split("\\|", -1);
                        boolean antesSinFecha = a.length < 3 || a[1].isEmpty() || a[2].isEmpty();
                        notificarAsignado(c, id, nombre, m, campos, antesSinFecha);
                    }
                }
                ed.putString(clave, firma);

                if (!jugado && inicio != Long.MIN_VALUE && inicio > ahora && inicio < proximoInicio) {
                    proximo = m; proximoInicio = inicio;
                }
                // Aviso previo para todos los partidos de los próximos 8 días
                if (!jugado && avPrevio && inicio != Long.MIN_VALUE) {
                    long cuando = inicio - horas * 3600000L;
                    if (cuando > ahora && inicio - ahora < 8L * 86400000L) {
                        String aid = clave;
                        programarPrevio(c, aid, cuando, inicio, id, nombre, m, campos);
                        alarmas.add(aid);
                    }
                }
            }
            if (primeraVez) ed.putBoolean("ini_" + id, true);
        }
        // Cancela avisos previos que ya no tocan (equipo dejado de seguir, partido movido, aviso desactivado)
        for (String a : alarmasAntes) if (!alarmas.contains(a)) cancelarPrevio(c, a);
        ed.putStringSet("alarmas", alarmas);
        ed.putLong("ultima_revision", ahora);
        ed.apply();
        return fallo ? Result.retry() : Result.success();
    }

    // ---------------------------------------------------------------- notificaciones
    private static PendingIntent abrirEquipo(Context c, String id, int n) {
        Intent abrir = new Intent(c, MainActivity.class).putExtra("equipo", id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, n, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notificar(Context c, String canal, int n, String titulo, String texto, PendingIntent pi, NotificationCompat.Action accion) {
        if (!puedeNotificar(c)) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, canal)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF0F5132)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setContentIntent(pi)
                .setAutoCancel(true);
        if (accion != null) b.addAction(accion);
        try { NotificationManagerCompat.from(c).notify(n, b.build()); } catch (SecurityException ignored) { }
    }

    private static void notificarResultado(Context c, String id, String nombre, JSONArray m) {
        boolean casa = id.equals(m.optString(3));
        int mios = casa ? m.optInt(7) : m.optInt(8), suyos = casa ? m.optInt(8) : m.optInt(7);
        String verbo = mios > suyos ? "✅ Victoria" : mios < suyos ? "❌ Derrota" : "🤝 Empate";
        String titulo = verbo + " · " + nombre;
        String texto = bonito(m.optString(4)) + " " + m.optInt(7) + " – " + m.optInt(8) + " " + bonito(m.optString(6))
                + "\nJornada " + m.optInt(0);
        int n = ("res_" + id + m.optInt(0)).hashCode();
        notificar(c, CANAL_RESULTADO, n, titulo, texto, abrirEquipo(c, id, n), null);
    }

    private static void notificarAsignado(Context c, String id, String nombre, JSONArray m, JSONObject campos, boolean nuevo) {
        boolean casa = id.equals(m.optString(3));
        String rival = bonito(casa ? m.optString(6) : m.optString(4));
        String cuando = fechaBonita(m.optString(1)) + (m.optString(2).isEmpty() ? "" : " · " + m.optString(2));
        String campo = campoTexto(campos, m);
        String titulo = (nuevo ? "📅 Partido asignado · " : "🔄 Cambio en el partido · ") + nombre;
        String texto = cuando + "\n" + (casa ? "En casa" : "Fuera") + " contra " + rival + (campo.isEmpty() ? "" : "\n📍 " + campo);
        int n = ("asig_" + id + m.optInt(0)).hashCode();
        notificar(c, CANAL_ASIGNADO, n, titulo, texto, abrirEquipo(c, id, n), accionMapa(c, campos, m, n));
    }

    static void mostrarPrevio(Context c, Intent in) {
        String id = in.getStringExtra("equipo");
        long inicio = in.getLongExtra("inicio", 0);
        if (id == null || System.currentTimeMillis() > inicio + 15 * 60000L) return;  // llegó demasiado tarde
        crearCanales(c);
        long falta = Math.max(0, Math.round((inicio - System.currentTimeMillis()) / 60000.0));
        String cuanto = falta >= 90 ? "en " + Math.round(falta / 60.0) + " h" : "en " + falta + " min";
        String titulo = "⚽ " + in.getStringExtra("nombre") + " juega " + cuanto;
        String texto = in.getStringExtra("hora") + " · " + in.getStringExtra("rival")
                + (in.getStringExtra("campo").isEmpty() ? "" : "\n📍 " + in.getStringExtra("campo"));
        int n = ("prev_" + id + inicio).hashCode();
        NotificationCompat.Action mapa = null;
        String geo = in.getStringExtra("mapa");
        if (geo != null && !geo.isEmpty()) {
            PendingIntent pm = PendingIntent.getActivity(c, n + 1, new Intent(Intent.ACTION_VIEW, Uri.parse(geo))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            mapa = new NotificationCompat.Action(0, "Cómo llegar", pm);
        }
        notificar(c, CANAL_PREVIO, n, titulo, texto, abrirEquipo(c, id, n), mapa);
    }

    // ---------------------------------------------------------------- alarmas
    private static PendingIntent intentPrevio(Context c, String aid, Intent extras, int flags) {
        Intent i = new Intent(c, PreAviso.class).setAction("previo:" + aid);
        if (extras != null) i.putExtras(extras);
        return PendingIntent.getBroadcast(c, aid.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void programarPrevio(Context c, String aid, long cuando, long inicio, String id, String nombre, JSONArray m, JSONObject campos) {
        boolean casa = id.equals(m.optString(3));
        Intent ex = new Intent()
                .putExtra("equipo", id).putExtra("nombre", nombre).putExtra("inicio", inicio)
                .putExtra("hora", fechaBonita(m.optString(1)) + " · " + m.optString(2))
                .putExtra("rival", (casa ? "En casa contra " : "Fuera contra ") + bonito(casa ? m.optString(6) : m.optString(4)))
                .putExtra("campo", campoTexto(campos, m))
                .putExtra("mapa", mapaUri(campos, m));
        PendingIntent pi = intentPrevio(c, aid, ex, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    private static void cancelarPrevio(Context c, String aid) {
        PendingIntent pi = intentPrevio(c, aid, null, PendingIntent.FLAG_NO_CREATE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (pi != null && am != null) { am.cancel(pi); pi.cancel(); }
    }

    // ---------------------------------------------------------------- utilidades
    private static JSONObject camposCache(Context c, SharedPreferences p) {
        long t = p.getLong("campos_t", 0);
        if (System.currentTimeMillis() - t > 24 * 3600000L) {
            try {
                String s = descargar(DATOS + "campos.json");
                new JSONObject(s);
                p.edit().putString("campos", s).putLong("campos_t", System.currentTimeMillis()).apply();
            } catch (Exception ignored) { }
        }
        try { return new JSONObject(p.getString("campos", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    private static String campoTexto(JSONObject campos, JSONArray m) {
        JSONArray cp = campos.optJSONArray(m.optString(9));
        if (cp != null) {
            String dir = cp.optString(1), loc = cp.optString(2);
            return cp.optString(0) + (dir.isEmpty() ? "" : ", " + dir) + (loc.isEmpty() ? "" : ", " + loc);
        }
        return m.optString(10);
    }

    private static String mapaUri(JSONObject campos, JSONArray m) {
        JSONArray cp = campos.optJSONArray(m.optString(9));
        if (cp != null && !cp.isNull(3) && !cp.isNull(4)) {
            String ll = cp.optDouble(3) + "," + cp.optDouble(4);
            return "geo:" + ll + "?q=" + ll + "(" + Uri.encode(cp.optString(0)) + ")";
        }
        String txt = campoTexto(campos, m);
        return txt.isEmpty() ? "" : "geo:0,0?q=" + Uri.encode(txt);
    }

    private static NotificationCompat.Action accionMapa(Context c, JSONObject campos, JSONArray m, int n) {
        String geo = mapaUri(campos, m);
        if (geo.isEmpty()) return null;
        PendingIntent pm = PendingIntent.getActivity(c, n + 1, new Intent(Intent.ACTION_VIEW, Uri.parse(geo))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Action(0, "Cómo llegar", pm);
    }

    /** "2026-11-14" + "10:00" en hora de Madrid -> milisegundos (Long.MIN_VALUE si no hay fecha u hora). */
    static long inicio(String fecha, String hora) {
        if (fecha == null || fecha.isEmpty() || hora == null || !hora.matches("\\d{1,2}:\\d{2}")) return Long.MIN_VALUE;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            f.setTimeZone(MADRID);
            Date d = f.parse(fecha + " " + hora);
            return d == null ? Long.MIN_VALUE : d.getTime();
        } catch (Exception e) { return Long.MIN_VALUE; }
    }

    static String fechaBonita(String fecha) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            in.setTimeZone(MADRID);
            SimpleDateFormat out = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es", "ES"));
            out.setTimeZone(MADRID);
            return out.format(in.parse(fecha));
        } catch (Exception e) { return fecha; }
    }

    /** "A.D. ALCORCON S.A.D. A" -> "A.D. Alcorcon S.A.D. A" */
    static String bonito(String n) {
        StringBuilder sb = new StringBuilder();
        for (String w : n.toLowerCase(new Locale("es", "ES")).split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (w.contains(".") || w.length() == 1) sb.append(w.toUpperCase(new Locale("es", "ES")));
            else sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    static String descargar(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "CanteraMadrid-Android");
        con.setUseCaches(false);
        try (InputStream in = con.getInputStream()) {
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }
}
