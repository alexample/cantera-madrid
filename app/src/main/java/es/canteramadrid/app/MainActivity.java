package es.canteramadrid.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una sola pantalla: la app (assets/index.html) dentro de un WebView.
 * Se sirve desde https://appassets.androidplatform.net/assets/, que es el origen
 * que acepta el worker de IA en Cloudflare.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int VERDE = Color.parseColor("#0F5132");

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(VERDE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Deja libres la barra de estado, la de navegación y el teclado.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets teclado = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, teclado.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // equipos seguidos y ajustes
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Enlaces externos (mapas) en su app o en el navegador.
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        // Ubicación para «¿Qué se juega aquí?»: solo cuando el usuario lo pide, con el permiso de Android
        s.setGeolocationEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean concedido = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (concedido) { callback.invoke(origin, true, false); return; }
                geoOrigen = origin; geoCallback = callback;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 8);
            }
        });

        web.addJavascriptInterface(new Puente(), "AndroidApp");
        Avisos.crearCanales(this);
        Avisos.programar(this);

        String equipo = getIntent() != null ? getIntent().getStringExtra("equipo") : null;
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(equipo != null ? START + "#e-" + equipo : START);
    }

    /** Al tocar una notificación con la app abierta: ir a la ficha del equipo. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String equipo = intent.getStringExtra("equipo");
        if (equipo != null) web.evaluateJavascript("window.abrirEquipo && window.abrirEquipo(" + JSONObject.quote(equipo) + ")", null);
    }

    /** Lo que la página puede pedir a Android. */
    private class Puente {
        /** Equipos seguidos: [{"id","g","n"}]. Se revisan al momento para preparar los avisos. */
        @JavascriptInterface
        public void guardarEquipos(String json) {
            android.content.SharedPreferences p = getSharedPreferences(Avisos.PREFS, Context.MODE_PRIVATE);
            if (json.equals(p.getString("equipos", ""))) return;
            p.edit().putString("equipos", json).apply();
            Avisos.revisarYa(MainActivity.this);
        }
        /** Ajustes de avisos: {"resultado":bool,"asignado":bool,"previo":bool,"horas":int}. */
        @JavascriptInterface
        public void guardarAjustes(String json) {
            getSharedPreferences(Avisos.PREFS, Context.MODE_PRIVATE).edit().putString("ajustes", json).apply();
            Avisos.revisarYa(MainActivity.this);
        }
        @JavascriptInterface
        public String leerAjustes() {
            return getSharedPreferences(Avisos.PREFS, Context.MODE_PRIVATE).getString("ajustes", "{}");
        }
        /**
         * Lee UN acta de la RFFM cuando el usuario pulsa «Cronología» (como si la abriera en el navegador).
         * Solo acepta /acta-partido/<número>. Devuelve a la página el JSON "game" o null.
         */
        @JavascriptInterface
        public void acta(String codacta, String query, String cb) {
            if (codacta == null || !codacta.matches("\\d{1,12}") || query == null || !query.matches("[0-9a-z=&]{0,120}")) return;
            new Thread(() -> {
                String rr = null;
                try { rr = leerActaRFFM(codacta, query, WebSettings.getDefaultUserAgent(MainActivity.this)); } catch (Throwable ignored) { }
                final String r = rr;
                runOnUiThread(() -> { try { web.evaluateJavascript("window.__acta && window.__acta(" + JSONObject.quote(cb) + ","
                        + (r == null ? "null" : JSONObject.quote(r)) + ")", null); } catch (Throwable ignored) { } });
            }).start();
        }

        /** Actas descargadas de noche por el trabajo en segundo plano: se entregan y se vacía el fichero. */
        @JavascriptInterface
        public String actasNoche() {
            try {
                java.io.File f = new java.io.File(getFilesDir(), Avisos.FICHERO_NOCHE);
                if (!f.exists()) return "{}";
                String t = Avisos.leerFichero(f);
                f.delete();
                return t;
            } catch (Exception e) { return "{}"; }
        }

        /** Jugadores seguidos (código de la federación, equipo y grupo) para sus avisos tras cada partido. */
        @JavascriptInterface
        public void guardarJugadores(String json) {
            getSharedPreferences(Avisos.PREFS, Context.MODE_PRIVATE).edit().putString("jugadores", json == null ? "[]" : json).apply();
            Avisos.programar(MainActivity.this);
        }

        /** Grupos extra (de jugadores seguidos) para las actas de noche. */
        @JavascriptInterface
        public void guardarGruposNoche(String json) {
            getSharedPreferences(Avisos.PREFS, Context.MODE_PRIVATE).edit().putString("grupos_noche", json == null ? "[]" : json).apply();
        }
        /** Color de fondo detrás de las barras del sistema (según el tema de la app). */
        /** Plantilla de un equipo desde su ficha de la RFFM: solo [código, nombre, dorsal] de cada jugador, para poner
         *  nombre a los datos de las actas compartidas (que van sin nombres). Lo pide el usuario al abrir estadísticas. */
        @JavascriptInterface
        public void plantilla(String equipo, String cb) {
            if (equipo == null || !equipo.matches("\\d{1,12}")) return;
            new Thread(() -> {
                String res = null;
                try {
                    String html = leer("https://www.rffm.es/fichaequipo/" + equipo);
                    Matcher m = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
                    if (m.find()) {
                        JSONObject pp = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps");
                        JSONArray jug = new JSONArray();
                        java.util.Set<String> vistos = new java.util.HashSet<>();
                        buscarJugadores(pp, jug, vistos, 0);
                        JSONObject r = new JSONObject();
                        r.put("jug", jug);
                        r.put("esquema", esquema(pp, 0));
                        res = r.toString();
                    }
                } catch (Throwable ignored) { }
                final String r = res;
                runOnUiThread(() -> { try { web.evaluateJavascript("window.__plantilla && window.__plantilla(" + JSONObject.quote(cb) + ","
                        + (r == null ? "null" : JSONObject.quote(r)) + ")", null); } catch (Throwable ignored) { } });
            }).start();
        }

        @JavascriptInterface
        public void colorBarras(String hex) {
            runOnUiThread(() -> {
                try { ((android.view.View) web.getParent()).setBackgroundColor(Color.parseColor(hex)); } catch (Exception ignored) { }
            });
        }
        /** Pide permiso de notificaciones (Android 13+). */
        @JavascriptInterface
        public void pedirPermiso() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7));
            }
        }
        @JavascriptInterface
        public boolean puedeNotificar() {
            return Avisos.puedeNotificar(MainActivity.this);
        }
        /** Abre los ajustes de notificaciones de la app. */
        @JavascriptInterface
        public void abrirAjustesNotificaciones() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                    startActivity(i);
                } catch (Exception ignored) { }
            });
        }
    }

    // Recorre la página buscando objetos que parezcan jugadores (con código y nombre)
    private static void buscarJugadores(Object o, JSONArray out, java.util.Set<String> vistos, int prof) {
        if (prof > 8 || o == null) return;
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length() && i < 400; i++) buscarJugadores(a.opt(i), out, vistos, prof + 1);
        } else if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String cod = null, nom = null, dor = null;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next(), kl = k.toLowerCase();
                Object v = j.opt(k);
                if (v instanceof JSONObject || v instanceof JSONArray) { buscarJugadores(v, out, vistos, prof + 1); continue; }
                String sv = String.valueOf(v);
                if (cod == null && kl.matches("cod_?jugador|codigo_?jugador|id_?jugador|codjugador")) cod = sv;
                else if (nom == null && kl.matches("nombre_?jugador|nombre|jugador|nombre_completo")) nom = sv;
                else if (dor == null && kl.matches("dorsal|numero|num_dorsal")) dor = sv;
            }
            if (cod != null && nom != null && cod.matches("\\d{1,12}") && vistos.add(cod)) {
                JSONArray f = new JSONArray(); f.put(cod); f.put(nom); f.put(dor == null ? JSONObject.NULL : dor); out.put(f);
            }
        }
    }

    // Solo los nombres de los campos (sin valores) para saber cómo es la página
    private static Object esquema(Object o, int prof) {
        try {
            if (prof > 5) return "…";
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o, r = new JSONObject();
                java.util.Iterator<String> it = j.keys();
                int n = 0;
                while (it.hasNext() && n++ < 40) { String k = it.next(); r.put(k, esquema(j.opt(k), prof + 1)); }
                return r;
            }
            if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o, r = new JSONArray();
                if (a.length() > 0) r.put(esquema(a.opt(0), prof + 1));
                return r;
            }
            return o == null || o == JSONObject.NULL ? "null" : o instanceof Number ? "num" : o instanceof Boolean ? "bool" : "txt";
        } catch (Exception e) { return "?"; }
    }

    /** Lee un acta de la RFFM y devuelve solo lo deportivo (goles, tarjetas, cambios y alineaciones), o null. */
    static String leerActaRFFM(String codacta, String query, String ua) {
        try {  // Throwable: cualquier fallo de red o de memoria devuelve null en vez de cerrar la app
            String html = leerCon("https://www.rffm.es/acta-partido/" + codacta + (query.isEmpty() ? "" : "?" + query), ua);
            Matcher m = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL).matcher(html);
            if (!m.find()) return null;
            JSONObject game = new JSONObject(m.group(1)).getJSONObject("props").getJSONObject("pageProps").optJSONObject("game");
            if (game == null) return null;
            JSONObject dep = new JSONObject();
            java.util.Iterator<String> it = game.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k.matches("(goles|tarjetas|jugadores)_equipo_(local|visitante)|goles_(local|visitante)|.*(sustitu|cambio|suplen|banquillo|entra|sale|alinea).*"))
                    dep.put(k, game.get(k));
            }
            return dep.length() == 0 ? null : dep.toString();
        } catch (Throwable e) { return null; }
    }

    static String leerCon(String url, String ua) throws Exception {
        java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        if (ua != null) con.setRequestProperty("User-Agent", ua);
        con.setRequestProperty("Accept-Language", "es-ES,es;q=0.9");
        try (java.io.InputStream in = con.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { con.disconnect(); }
    }

    private String leer(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(this));
        con.setRequestProperty("Accept-Language", "es-ES,es;q=0.9");
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            con.disconnect();
        }
    }

    private String geoOrigen;
    private GeolocationPermissions.Callback geoCallback;

    @Override
    public void onRequestPermissionsResult(int code, String[] permisos, int[] res) {
        super.onRequestPermissionsResult(code, permisos, res);
        if (code == 8 && geoCallback != null) {
            boolean ok = false;
            for (int r : res) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            geoCallback.invoke(geoOrigen, ok, false);
            geoCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        Avisos.revisarYa(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Atrás cierra la hoja abierta o vuelve al inicio; en el inicio, sale de la app.
        web.evaluateJavascript("window.atras ? window.atras() : false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }
}
