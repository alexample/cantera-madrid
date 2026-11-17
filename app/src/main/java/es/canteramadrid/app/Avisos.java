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
    static final String CANAL_METEO = "mal_tiempo";
    static final String CANAL_JUGADOR = "jugadores";
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
        NotificationChannel mt = new NotificationChannel(CANAL_METEO, "Mal tiempo para el partido", NotificationManager.IMPORTANCE_DEFAULT);
        mt.setDescription("Unas 24 h antes, si se prevé lluvia, nieve, tormenta, viento fuerte o hay aviso meteorológico.");
        nm.createNotificationChannel(mt);
        NotificationChannel jg = new NotificationChannel(CANAL_JUGADOR, "Jugadores que sigues", NotificationManager.IMPORTANCE_DEFAULT);
        jg.setDescription("Al terminar el partido: si fue titular, suplente o no convocado, sus goles y sus tarjetas.");
        nm.createNotificationChannel(jg);
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
        boolean avMeteo = aj.optBoolean("meteo", true);
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
            // Etiqueta del usuario («Adrián», «Mi hijo»): se antepone en los avisos
            String etiqueta = eq.optString("e", "").trim();
            if (!etiqueta.isEmpty()) nombre = etiqueta + " · " + nombre;
            // Ajustes propios del equipo (si los tiene); si no, los generales
            JSONObject av = eq.optJSONObject("av");
            boolean eResultado = av != null ? av.optBoolean("resultado", avResultado) : avResultado;
            boolean eAsignado = av != null ? av.optBoolean("asignado", avAsignado) : avAsignado;
            boolean ePrevio = av != null ? av.optBoolean("previo", avPrevio) : avPrevio;
            boolean eMeteo = av != null ? av.optBoolean("meteo", avMeteo) : avMeteo;
            int eHoras = av != null ? av.optInt("horas", horas) : horas;
            if (eHoras < 1 || eHoras > 12) eHoras = horas;
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
                    if (jugado && antes.startsWith("P") && eResultado && inicio > ahora - 4L * 86400000L) {
                        notificarResultado(c, id, nombre, m);
                    } else if (!jugado && eAsignado && inicio > ahora && !m.optString(1).isEmpty()) {
                        String[] a = antes.split("\\|", -1);
                        boolean antesSinFecha = a.length < 3 || a[1].isEmpty() || a[2].isEmpty();
                        notificarAsignado(c, id, nombre, m, campos, antesSinFecha);
                    }
                }
                ed.putString(clave, firma);

                if (!jugado && inicio != Long.MIN_VALUE && inicio > ahora && inicio < proximoInicio) {
                    proximo = m; proximoInicio = inicio;
                }
                // Mal tiempo: entre 24 h y 2 h antes, una sola vez por partido
                if (!jugado && eMeteo && inicio != Long.MIN_VALUE && inicio - ahora <= 24 * 3600000L && inicio - ahora > 2 * 3600000L
                        && !p.getBoolean("met_" + clave, false)) {
                    String motivo = malTiempo(campos, m, inicio);
                    if (motivo != null) {
                        notificarMeteo(c, id, nombre, m, campos, motivo);
                        ed.putBoolean("met_" + clave, true);
                    }
                }
                // Aviso previo para todos los partidos de los próximos 8 días
                if (!jugado && ePrevio && inicio != Long.MIN_VALUE) {
                    long cuando = inicio - eHoras * 3600000L;
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
        // Jugadores seguidos: resumen de su partido en cuanto el acta está disponible
        try { avisosJugadores(c, p, cacheGrupos, ahora); } catch (Throwable ignored) { }
        // De madrugada y con wifi: actas nuevas de las divisiones que sigues, poco a poco
        try { actasDeNoche(c, p, cacheGrupos, ahora); } catch (Throwable ignored) { }
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

    // ---------------------------------------------------------------- mal tiempo
    private JSONArray alertasCache = null;

    /** Devuelve el motivo («Lluvia 70 %…», «Aviso amarillo por viento…») o null si el tiempo es bueno. */
    private String malTiempo(JSONObject campos, JSONArray m, long inicio) {
        JSONArray cp = campos.optJSONArray(m.optString(9));
        if (cp == null || cp.isNull(3) || cp.isNull(4)) return null;
        double lat = cp.optDouble(3), lon = cp.optDouble(4);
        StringBuilder r = new StringBuilder();
        try {
            int H = Integer.parseInt(m.optString(2).split(":")[0]);
            JSONObject h = null;
            // Primero AEMET (vía el worker de la app); si no cubre la hora del partido, Open-Meteo
            try {
                JSONObject a = new JSONObject(descargar("https://dondeloechan-ia.alexample.workers.dev/cantera-tiempo?lat=" + lat + "&lon=" + lon + "&fecha=" + m.optString(1)));
                JSONArray cubre = a.optJSONArray("cubre");
                boolean ok = false;
                for (int i = 0; cubre != null && i < cubre.length(); i++) if (cubre.optInt(i) == H) ok = true;
                if (ok) h = a.getJSONObject("hourly");
            } catch (Exception ignored) { }
            if (h == null) {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&hourly=precipitation_probability,precipitation,snowfall,weather_code,wind_speed_10m,wind_gusts_10m"
                        + "&timezone=Europe%2FMadrid&start_date=" + m.optString(1) + "&end_date=" + m.optString(1);
                h = new JSONObject(descargar(url)).getJSONObject("hourly");
            }
            JSONArray vacio = new JSONArray();
            int prob = 0, cod = 0; double mm = 0, nieve = 0, viento = 0, racha = 0;
            for (int k = H; k <= Math.min(23, H + 1); k++) {
                prob = Math.max(prob, (h.has("precipitation_probability") ? h.getJSONArray("precipitation_probability") : vacio).optInt(k));
                mm += (h.has("precipitation") ? h.getJSONArray("precipitation") : vacio).optDouble(k, 0);
                nieve += (h.has("snowfall") ? h.getJSONArray("snowfall") : vacio).optDouble(k, 0);
                cod = Math.max(cod, (h.has("weather_code") ? h.getJSONArray("weather_code") : vacio).optInt(k));
                viento = Math.max(viento, (h.has("wind_speed_10m") ? h.getJSONArray("wind_speed_10m") : vacio).optDouble(k, 0));
                racha = Math.max(racha, (h.has("wind_gusts_10m") ? h.getJSONArray("wind_gusts_10m") : vacio).optDouble(k, 0));
            }
            if (cod >= 95) r.append("⛈️ Tormenta");
            else if (nieve > 0 || (cod >= 71 && cod <= 77) || cod == 85 || cod == 86) r.append("🌨️ Nieve");
            else if (prob >= 50 || mm >= 0.5) r.append("🌧️ Lluvia ").append(prob).append(" %").append(mm >= 0.1 ? String.format(Locale.US, " (%.1f mm)", mm) : "");
            if (racha >= 50 || viento >= 35) {
                if (r.length() > 0) r.append(" · ");
                r.append("💨 Viento ").append(Math.round(viento)).append(" km/h, rachas de ").append(Math.round(racha));
            }
        } catch (Exception ignored) { }
        String alerta = alertaMadrid(inicio);
        if (alerta != null) {
            if (r.length() > 0) r.append("\n");
            r.append(alerta);
        }
        return r.length() > 0 ? r.toString() : null;
    }

    /** Avisos oficiales (AEMET vía MeteoAlarm) para las zonas de Madrid que coinciden con la hora del partido. */
    private String alertaMadrid(long inicio) {
        try {
            if (alertasCache == null)
                alertasCache = new JSONObject(descargar("https://feeds.meteoalarm.org/api/v1/warnings/feeds-spain")).optJSONArray("warnings");
            if (alertasCache == null) return null;
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            String mejor = null; int nivel = 0;
            for (int i = 0; i < alertasCache.length(); i++) {
                JSONArray infos = alertasCache.optJSONObject(i).optJSONObject("alert").optJSONArray("info");
                if (infos == null) continue;
                JSONObject info = infos.optJSONObject(0);
                for (int j = 0; j < infos.length(); j++) if (infos.optJSONObject(j).optString("language").startsWith("es")) info = infos.optJSONObject(j);
                boolean madrid = false;
                JSONArray areas = info.optJSONArray("area");
                for (int a = 0; areas != null && a < areas.length(); a++) {
                    String d = areas.optJSONObject(a).optString("areaDesc").toLowerCase(Locale.ROOT);
                    if (d.contains("madrid") || d.contains("metropolitana y henares") || d.contains("sur, vegas y oeste")) madrid = true;
                }
                if (!madrid) continue;
                long on = iso.parse(info.optString("onset", info.optString("effective"))).getTime();
                long off = iso.parse(info.optString("expires")).getTime();
                if (off < inicio - 3600000L || on > inicio + 2 * 3600000L) continue;
                int n = 0; String color = "";
                JSONArray par = info.optJSONArray("parameter");
                for (int k = 0; par != null && k < par.length(); k++) {
                    JSONObject x = par.optJSONObject(k);
                    if ("awareness_level".equals(x.optString("valueName"))) {
                        String v = x.optString("value").toLowerCase(Locale.ROOT);
                        n = v.contains("red") ? 4 : v.contains("orange") ? 3 : v.contains("yellow") ? 2 : 1;
                        color = n == 4 ? "rojo" : n == 3 ? "naranja" : n == 2 ? "amarillo" : "";
                    }
                }
                if (n >= 2 && n > nivel) { nivel = n; mejor = "⚠️ Aviso " + color + ": " + info.optString("event", info.optString("headline")); }
            }
            return mejor;
        } catch (Exception e) { return null; }
    }

    private static void notificarMeteo(Context c, String id, String nombre, JSONArray m, JSONObject campos, String motivo) {
        boolean casa = id.equals(m.optString(3));
        String rival = bonito(casa ? m.optString(6) : m.optString(4));
        String titulo = "🌦️ Mal tiempo para el partido · " + nombre;
        String texto = motivo + "\n" + fechaBonita(m.optString(1)) + " · " + m.optString(2) + " · " + (casa ? "en casa contra " : "fuera contra ") + rival
                + (campoTexto(campos, m).isEmpty() ? "" : "\n📍 " + campoTexto(campos, m));
        int n = ("met_" + id + m.optInt(0)).hashCode();
        notificar(c, CANAL_METEO, n, titulo, texto, abrirEquipo(c, id, n), accionMapa(c, campos, m, n));
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

    // «Cómo llegar»: Google Maps busca el campo por nombre y dirección y elige su propia ficha
    private static String mapaUri(JSONObject campos, JSONArray m) {
        JSONArray cp = campos.optJSONArray(m.optString(9));
        String nombre = cp != null ? cp.optString(0) : m.optString(10);
        String n = nombre.replaceAll("\\([^)]*\\)", " ").replaceAll("(?i)\\bF-?(7|11)\\b", " ").replaceAll("\\s+\\d+\\s*$", "")
                .replaceAll("(?i)\\bPVO\\.?\\s*", "Polideportivo ").replaceAll("(?i)\\bPOLID(\\.\\s*|\\s+)", "Polideportivo ")
                .replaceAll("\\s*-\\s*", " ").replaceAll("\\s+", " ").trim();
        if (!n.toLowerCase().matches(".*(campo|estadio|polideportivo|ciudad deportiva|centro deportivo|instalaci).*") && !n.isEmpty()) n = "Campo de fútbol " + n;
        StringBuilder q = new StringBuilder(n);
        if (cp != null) for (int i = 1; i <= 2; i++) { String x = cp.optString(i); if (!x.isEmpty() && !x.equals("null")) q.append(", ").append(x); }
        try {
            return "https://www.google.com/maps/dir/?api=1&destination=" + java.net.URLEncoder.encode(q.toString(), "UTF-8") + "&travelmode=driving";
        } catch (Exception e) {
            return "https://www.google.com/maps/search/?api=1&query=" + q;
        }
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

    // ---------- Jugadores seguidos ----------
    // Para cada jugador seguido (con su código de la federación), en los partidos de su equipo ya terminados en los
    // últimos 3 días: se busca el acta (primero en el servidor compartido, si no en la federación) y se avisa una vez
    // con titular / suplente / no convocado, goles con minuto y tarjetas.
    private void avisosJugadores(Context c, SharedPreferences p, JSONObject cacheGrupos, long ahora) throws Exception {
        JSONArray js = new JSONArray(p.getString("jugadores", "[]"));
        if (js.length() == 0) return;
        Set<String> hechos = new HashSet<>(p.getStringSet("jug_avisados", new HashSet<>()));
        java.io.File f = new java.io.File(c.getFilesDir(), FICHERO_NOCHE);
        JSONObject pend = null;
        JSONObject srvCache = new JSONObject();
        int lecturas = 0;
        String ua = android.webkit.WebSettings.getDefaultUserAgent(c);
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        String limite = iso.format(new java.util.Date(ahora - 3L * 86400000L));
        for (int i = 0; i < js.length(); i++) {
            JSONObject j = js.optJSONObject(i);
            if (j == null) continue;
            String cod = j.optString("cod"), tid = j.optString("tid"), gid = j.optString("gid"), nom = j.optString("n");
            if (!cod.matches("\\d{1,12}") || gid.isEmpty()) continue;
            JSONObject g = cacheGrupos.optJSONObject(gid);
            if (g == null) { try { g = new JSONObject(descargar(DATOS + "g/" + gid + ".json")); cacheGrupos.put(gid, g); } catch (Exception e) { continue; } }
            JSONArray part = g.optJSONArray("part");
            if (part == null) continue;
            for (int k = 0; k < part.length(); k++) {
                JSONArray m = part.optJSONArray(k);
                if (m == null || m.length() < 12 || m.isNull(7) || m.isNull(8)) continue;
                if (!tid.equals(m.optString(3)) && !tid.equals(m.optString(5))) continue;
                if (m.optString(1).compareTo(limite) < 0) continue;
                String ca = m.optString(11);
                if (ca.isEmpty() || hechos.contains(cod + "|" + ca)) continue;
                boolean local = tid.equals(m.optString(3));
                // 1) servidor compartido (sin nombres, con códigos)
                JSONObject anon = null;
                try {
                    if (!srvCache.has(gid)) srvCache.put(gid, new JSONObject(descargar("https://dondeloechan-ia.alexample.workers.dev/cantera-actas?gid=" + gid)).optJSONObject("actas"));
                    JSONObject a = srvCache.optJSONObject(gid);
                    if (a != null) anon = a.optJSONObject(ca);
                } catch (Exception ignored) { }
                String texto = null;
                if (anon != null) texto = resumenAnon(anon.optJSONObject(local ? "l" : "v"), "c" + cod);
                else if (lecturas < 4) {
                    // 2) la federación (y se guarda para que la app la suba al servidor)
                    String q = "temporada=" + g.optString("temp") + "&competicion=" + g.optString("comp") + "&grupo=" + gid;
                    String game = MainActivity.leerActaRFFM(ca, q, ua);
                    lecturas++;
                    if (game != null) {
                        texto = resumenActa(new JSONObject(game), local ? "local" : "visitante", cod);
                        if (pend == null) pend = f.exists() ? new JSONObject(leerFichero(f)) : new JSONObject();
                        pend.put(ca, game);
                    }
                    Thread.sleep(2000);
                }
                if (texto == null) continue;   // acta aún sin publicar: se vuelve a mirar en la próxima pasada
                String marcador = m.optString(4) + " " + m.optInt(7) + "-" + m.optInt(8) + " " + m.optString(6);
                notificar(c, CANAL_JUGADOR, ("j" + cod + ca).hashCode(), nombreBonito(nom) + " · " + texto.split("\n")[0],
                        marcador + (texto.contains("\n") ? "\n" + texto.substring(texto.indexOf('\n') + 1) : ""), abrirEquipo(c, tid, ("j" + cod).hashCode()), null);
                hechos.add(cod + "|" + ca);
            }
        }
        if (hechos.size() > 3000) hechos.clear();
        p.edit().putStringSet("jug_avisados", hechos).apply();
        if (pend != null) try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(pend.toString().getBytes("UTF-8")); }
    }

    private static String nombreBonito(String n) {
        String[] pz = n.split(",", 2);
        String s = pz.length == 2 ? pz[1].trim() + " " + pz[0].trim() : n;
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(java.util.Locale.ROOT).split("\\s+")) if (!w.isEmpty()) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }

    private static String minutos(java.util.List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String m : l) { if (b.length() > 0) b.append(", "); b.append(m == null || m.isEmpty() ? "?" : m + "'"); }
        return b.toString();
    }

    private static String componer(boolean conv, boolean tit, boolean jugo, java.util.List<String> goles, java.util.List<String> am, java.util.List<String> ro) {
        if (!conv) return "No convocado";
        StringBuilder t = new StringBuilder(tit ? "Titular" : jugo ? "Suplente · salió al campo" : "Suplente");
        if (!goles.isEmpty()) t.append(" · ").append(goles.size()).append(goles.size() == 1 ? " gol" : " goles");
        if (!am.isEmpty()) t.append(" · ").append(am.size() == 1 ? "amarilla" : am.size() + " amarillas");
        if (!ro.isEmpty()) t.append(" · roja");
        StringBuilder d = new StringBuilder();
        if (!goles.isEmpty()) d.append("⚽ ").append(minutos(goles));
        if (!am.isEmpty()) d.append(d.length() > 0 ? "   " : "").append("🟨 ").append(minutos(am));
        if (!ro.isEmpty()) d.append(d.length() > 0 ? "   " : "").append("🟥 ").append(minutos(ro));
        return d.length() > 0 ? t + "\n" + d : t.toString();
    }

    // Acta compartida: {jug:[[clave,dorsal,tit,por,cap,part]], gol:[[clave,min,pp]], tar:[[clave,min,tipo]]}
    private static String resumenAnon(JSONObject l, String clave) {
        if (l == null) return null;
        boolean conv = false, tit = false, jugo = false;
        JSONArray jug = l.optJSONArray("jug");
        for (int i = 0; jug != null && i < jug.length(); i++) { JSONArray x = jug.optJSONArray(i); if (x != null && clave.equals(x.optString(0))) { conv = true; tit = x.optInt(2) == 1; jugo = tit || x.optInt(5) == 1; } }
        java.util.List<String> goles = new java.util.ArrayList<>(), am = new java.util.ArrayList<>(), ro = new java.util.ArrayList<>();
        JSONArray gl = l.optJSONArray("gol");
        for (int i = 0; gl != null && i < gl.length(); i++) { JSONArray x = gl.optJSONArray(i); if (x != null && clave.equals(x.optString(0)) && x.optInt(2) == 0) goles.add(x.isNull(1) ? null : String.valueOf(x.optInt(1))); }
        JSONArray tr = l.optJSONArray("tar");
        for (int i = 0; tr != null && i < tr.length(); i++) { JSONArray x = tr.optJSONArray(i); if (x != null && clave.equals(x.optString(0))) { String mn = x.isNull(1) ? null : String.valueOf(x.optInt(1)); if ("a".equals(x.optString(2))) am.add(mn); else ro.add(mn); } }
        return componer(conv, tit, jugo, goles, am, ro);
    }

    // Acta de la federación (campos con nombre)
    private static String resumenActa(JSONObject g, String suf, String cod) {
        boolean conv = false, tit = false, jugo = false;
        JSONArray jug = g.optJSONArray("jugadores_equipo_" + suf);
        for (int i = 0; jug != null && i < jug.length(); i++) { JSONObject x = jug.optJSONObject(i); if (x != null && cod.equals(x.optString("codjugador"))) { conv = true; tit = si(x.optString("titular")); jugo = tit || si(x.optString("participacion")); } }
        java.util.List<String> goles = new java.util.ArrayList<>(), am = new java.util.ArrayList<>(), ro = new java.util.ArrayList<>();
        JSONArray gl = g.optJSONArray("goles_equipo_" + suf);
        for (int i = 0; gl != null && i < gl.length(); i++) { JSONObject x = gl.optJSONObject(i); if (x != null && cod.equals(x.optString("codjugador"))) goles.add(x.optString("minuto")); }
        JSONArray tr = g.optJSONArray("tarjetas_equipo_" + suf);
        for (int i = 0; tr != null && i < tr.length(); i++) { JSONObject x = tr.optJSONObject(i); if (x != null && cod.equals(x.optString("codjugador"))) {
            String t = x.optString("codigo_tipo_amonestacion"); boolean roja = "101".equals(t) || "102".equals(t) || si(x.optString("segunda_amarilla"));
            (roja ? ro : am).add(x.optString("minuto")); } }
        return componer(conv, tit, jugo, goles, am, ro);
    }

    private static boolean si(String v) { return "1".equals(v) || "S".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v); }

    // ---------- Actas de noche ----------
    // Entre la 1 y las 7, con wifi, en cada pasada (cada 30 min) se leen como mucho 12 actas nuevas de la federación,
    // despacio. Antes se mira qué actas ya tiene el servidor compartido para no pedirlas. Se guardan en un fichero
    // que la app recoge al abrirse (las sube sin nombres y las usa para las estadísticas de jugadores).
    static final String FICHERO_NOCHE = "actas_noche.json";
    private void actasDeNoche(Context c, SharedPreferences p, JSONObject cacheGrupos, long ahora) throws Exception {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Madrid"));
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        if (h < 1 || h >= 7) return;
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.isActiveNetworkMetered()) return;   // solo con wifi
        Set<String> gids = new java.util.LinkedHashSet<>();
        java.util.Iterator<String> it = cacheGrupos.keys();
        while (it.hasNext()) gids.add(it.next());
        try { JSONArray extra = new JSONArray(p.getString("grupos_noche", "[]")); for (int i = 0; i < extra.length(); i++) gids.add(extra.optString(i)); } catch (Exception ignored) { }
        Set<String> vistas = new HashSet<>(p.getStringSet("actas_vistas", new HashSet<>()));
        java.io.File f = new java.io.File(c.getFilesDir(), FICHERO_NOCHE);
        JSONObject pend = f.exists() ? new JSONObject(Avisos.leerFichero(f)) : new JSONObject();
        String ua = android.webkit.WebSettings.getDefaultUserAgent(c);
        int leidas = 0, fallos = 0;
        for (String gid : gids) {
            if (gid.isEmpty() || leidas >= 12 || fallos >= 3) break;
            JSONObject g = cacheGrupos.optJSONObject(gid);
            if (g == null) { try { g = new JSONObject(descargar(DATOS + "g/" + gid + ".json")); } catch (Exception e) { continue; } }
            // lo que ya está en el servidor compartido no hace falta pedirlo a la federación
            try {
                JSONObject srv = new JSONObject(descargar("https://dondeloechan-ia.alexample.workers.dev/cantera-actas?gid=" + gid)).optJSONObject("actas");
                if (srv != null) { java.util.Iterator<String> ks = srv.keys(); while (ks.hasNext()) vistas.add(ks.next()); }
            } catch (Exception ignored) { }
            JSONArray part = g.optJSONArray("part");
            if (part == null) continue;
            String q = "temporada=" + g.optString("temp") + "&competicion=" + g.optString("comp") + "&grupo=" + gid;
            for (int i = 0; i < part.length() && leidas < 12 && fallos < 3; i++) {
                JSONArray m = part.optJSONArray(i);
                if (m == null || m.isNull(7) || m.length() < 12) continue;
                String ca = m.optString(11);
                if (ca.isEmpty() || !ca.matches("\\d{1,12}") || vistas.contains(ca) || pend.has(ca)) continue;
                String game = MainActivity.leerActaRFFM(ca, q, ua);
                leidas++;
                if (game == null) { fallos++; Thread.sleep(5000L * fallos); continue; }
                fallos = 0;
                pend.put(ca, game);
                vistas.add(ca);
                Thread.sleep(3000);
            }
        }
        if (vistas.size() > 6000) vistas.clear();
        p.edit().putStringSet("actas_vistas", vistas).apply();
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(pend.toString().getBytes("UTF-8")); }
    }

    static String leerFichero(java.io.File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
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
