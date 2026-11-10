package br.com.redeclara.diagnostico;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Diagnostico local de conectividade. Nao modifica identidade de rede,
 * credenciais do Android, politicas do portal ou configuracoes da VPN.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "redeclara_local_v1";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_SNAPSHOT = "snapshot";
    private static final int MAX_HISTORY_CHARS = 7000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ConnectivityManager cm;
    private SharedPreferences prefs;
    private TextView statusTitle, statusDetail, statusTags, networkDetails;
    private TextView testResults, comparisonResults, history;
    private Button testButton;
    private boolean callbackRegistered = false;
    private String lastStateSignature = "";

    private final ConnectivityManager.NetworkCallback callback =
            new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { scheduleRefresh(); }
        @Override public void onLost(Network n) { scheduleRefresh(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { scheduleRefresh(); }
        @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { scheduleRefresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        final View root = findViewById(R.id.root);
        // Android 15: manter o conteudo fora das barras com edge-to-edge.
        final int left = root.getPaddingLeft(), top = root.getPaddingTop();
        final int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(left + sys.left, top + sys.top,
                    right + sys.right, bottom + sys.bottom);
            return insets;
        });

        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        statusTags = findViewById(R.id.statusTags);
        networkDetails = findViewById(R.id.networkDetails);
        testResults = findViewById(R.id.testResults);
        comparisonResults = findViewById(R.id.comparisonResults);
        history = findViewById(R.id.history);
        testButton = findViewById(R.id.testButton);
        findViewById(R.id.refreshButton).setOnClickListener(v -> refresh(true));
        testButton.setOnClickListener(v -> runProbes());
        findViewById(R.id.saveSnapshotButton).setOnClickListener(v -> saveSnapshot());
        findViewById(R.id.compareButton).setOnClickListener(v -> compareSnapshot());
        findViewById(R.id.wifiButton).setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        findViewById(R.id.vpnButton).setOnClickListener(v -> openSettings("android.settings.VPN_SETTINGS"));
        findViewById(R.id.shareButton).setOnClickListener(v -> shareReport());
        findViewById(R.id.clearButton).setOnClickListener(v -> confirmClear());
        refreshHistory();
        refresh(false);
    }

    @Override protected void onStart() {
        super.onStart();
        if (!callbackRegistered && cm != null) {
            try {
                cm.registerDefaultNetworkCallback(callback);
                callbackRegistered = true;
            } catch (RuntimeException e) {
                note("Monitor automático indisponível: " + simpleError(e));
            }
        }
        refresh(false);
    }

    @Override protected void onStop() {
        if (callbackRegistered) {
            try { cm.unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) { }
            callbackRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void scheduleRefresh() { mainHandler.post(() -> refresh(false)); }

    private void refresh(boolean manual) {
        try {
            JSONObject snap = captureSnapshot();
            boolean wifi = "Sim".equals(snap.optString("Wi-Fi conectado"));
            boolean vpn = "Sim".equals(snap.optString("VPN ativa"));
            boolean captive = "Sim".equals(snap.optString("Portal identificado"));
            boolean valid = "Sim".equals(snap.optString("Wi-Fi validado"));
            if (!wifi) {
                statusTitle.setText("Wi-Fi não detectado");
                statusDetail.setText("Conecte-se a uma rede Wi-Fi para acompanhar a sessão e comparar informações.");
            } else if (captive) {
                statusTitle.setText("Portal de acesso detectado");
                statusDetail.setText("O Android identificou um portal. Abra as configurações de Wi-Fi para autenticar normalmente.");
            } else if (valid) {
                statusTitle.setText("Wi-Fi com acesso validado");
                statusDetail.setText("O sistema Android considerou esta rede com acesso à internet. Isso não garante acesso a todos os sites.");
            } else {
                statusTitle.setText("Wi-Fi conectado · acesso limitado");
                statusDetail.setText("O sistema ainda não validou internet geral. Redes com acesso apenas a alguns serviços podem aparecer assim.");
            }
            statusTags.setText("Wi-Fi: " + boolLabel(wifi) + "     VPN: " + boolLabel(vpn)
                    + "\nPortal: " + boolLabel(captive) + "     Validação: " + boolLabel(valid));
            networkDetails.setText(formatDetails(snap));
            String signature = snap.optString("Wi-Fi conectado") + "|"
                    + snap.optString("VPN ativa") + "|" + snap.optString("Portal identificado")
                    + "|" + snap.optString("Wi-Fi validado") + "|" + snap.optString("IP Wi-Fi");
            if (!signature.equals(lastStateSignature)) {
                lastStateSignature = signature;
                note("Estado: Wi-Fi=" + boolLabel(wifi) + ", VPN=" + boolLabel(vpn)
                        + ", portal=" + boolLabel(captive) + ", validado=" + boolLabel(valid));
            }
            if (manual) { note("Consulta manual realizada."); toast("Informações atualizadas"); }
        } catch (RuntimeException e) {
            networkDetails.setText("Não foi possível consultar a rede: " + simpleError(e));
        }
    }

    private JSONObject captureSnapshot() {
        JSONObject o = new JSONObject();
        Network active = safeActive();
        Network wifi = findWifi();
        NetworkCapabilities activeCaps = caps(active);
        NetworkCapabilities wifiCaps = caps(wifi);
        LinkProperties lp = props(wifi != null ? wifi : active);
        boolean hasWifi = wifi != null;
        boolean hasVpn = activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        put(o, "Momento", stamp());
        put(o, "Rede padrão", transports(activeCaps));
        put(o, "Wi-Fi conectado", hasWifi ? "Sim" : "Não");
        put(o, "Wi-Fi validado", wifiCaps != null && wifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "Sim" : "Não");
        put(o, "Portal identificado", wifiCaps != null && wifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ? "Sim" : "Não");
        put(o, "VPN ativa", hasVpn ? "Sim" : "Não");
        put(o, "IP Wi-Fi", ipInfo(lp));
        put(o, "Gateway", gatewayInfo(lp));
        put(o, "DNS", dnsInfo(lp));
        put(o, "DNS privado", privateDnsInfo(lp));
        put(o, "MTU", lp == null ? "—" : Integer.toString(lp.getMtu()));
        return o;
    }

    private static void put(JSONObject o, String k, String v) {
        try { o.put(k, v); } catch (JSONException ignored) { }
    }

    private Network safeActive() {
        try { return cm == null ? null : cm.getActiveNetwork(); }
        catch (RuntimeException ex) { return null; }
    }
    private Network findWifi() {
        if (cm == null) return null;
        try {
            Network active = cm.getActiveNetwork();
            NetworkCapabilities c = caps(active);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active;
            for (Network n : cm.getAllNetworks()) {
                c = caps(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    private NetworkCapabilities caps(Network n) {
        try { return n == null ? null : cm.getNetworkCapabilities(n); }
        catch (RuntimeException ignored) { return null; }
    }
    private LinkProperties props(Network n) {
        try { return n == null ? null : cm.getLinkProperties(n); }
        catch (RuntimeException ignored) { return null; }
    }
    private String transports(NetworkCapabilities c) {
        if (c == null) return "Nenhuma";
        List<String> entries = new ArrayList<>();
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) entries.add("Wi-Fi");
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) entries.add("VPN");
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) entries.add("Dados móveis");
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) entries.add("Ethernet");
        return entries.isEmpty() ? "Outro tipo" : join(entries);
    }
    private String ipInfo(LinkProperties lp) {
        if (lp == null) return "—";
        List<String> ips = new ArrayList<>();
        for (LinkAddress address : lp.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) {
                ips.add(address.getAddress().getHostAddress() + "/" + address.getPrefixLength());
            }
        }
        return ips.isEmpty() ? "Sem IPv4 informado" : join(ips);
    }
    private String dnsInfo(LinkProperties lp) {
        if (lp == null) return "—";
        List<String> out = new ArrayList<>();
        for (InetAddress dns : lp.getDnsServers()) {
            if (out.size() >= 3) break;
            out.add(dns.getHostAddress());
        }
        return out.isEmpty() ? "Não informado" : join(out);
    }
    private String gatewayInfo(LinkProperties lp) {
        if (lp == null) return "—";
        for (RouteInfo route : lp.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway != null && gateway instanceof Inet4Address && !gateway.isAnyLocalAddress()) {
                return gateway.getHostAddress();
            }
        }
        return "Não informado";
    }
    private String privateDnsInfo(LinkProperties lp) {
        if (lp == null) return "—";
        if (!lp.isPrivateDnsActive()) return "Inativo / não informado";
        String host = lp.getPrivateDnsServerName();
        return host == null || host.isEmpty() ? "Ativo (modo automático)" : "Ativo: " + host;
    }
    private String formatDetails(JSONObject o) {
        return "Rede principal: " + o.optString("Rede padrão")
                + "\nWi-Fi conectado: " + o.optString("Wi-Fi conectado")
                + "\nIP: " + o.optString("IP Wi-Fi")
                + "\nGateway: " + o.optString("Gateway")
                + "\nDNS: " + o.optString("DNS")
                + "\nDNS privado: " + o.optString("DNS privado")
                + "\nMTU: " + o.optString("MTU")
                + "\n\nMAC privado: consulte em Configurações › Wi-Fi › detalhes da rede."
                + "\nO Android não fornece o MAC real para aplicativos comuns.";
    }

    private void runProbes() {
        testButton.setEnabled(false);
        testButton.setText("Testando…");
        testResults.setText("Fazendo testes curtos. Isso não abre autorização no portal nem ativa VPN.");
        final Network wifi = findWifi();
        final Network active = safeActive();
        executor.execute(() -> {
            StringBuilder b = new StringBuilder();
            b.append("RESULTADOS ").append(stamp()).append("\n");
            if (wifi == null) {
                b.append("\nWi-Fi: não detectado.\n");
            } else {
                b.append("\nWI-FI (caminho direto)\n");
                b.append("HTTPS: ").append(probe(wifi, "https://connectivitycheck.gstatic.com/generate_204")).append("\n");
                b.append("HTTP: ").append(probe(wifi, "http://connectivitycheck.gstatic.com/generate_204")).append("\n");
            }
            if (active == null) {
                b.append("\nRede padrão: não identificada.\n");
            } else if (active.equals(wifi)) {
                b.append("\nRede padrão: igual ao Wi-Fi; testes acima se aplicam.\n");
            } else {
                b.append("\nREDE PADRÃO (pode incluir VPN)\n");
                b.append("HTTPS: ").append(probe(null, "https://connectivitycheck.gstatic.com/generate_204")).append("\n");
                b.append("HTTP: ").append(probe(null, "http://connectivitycheck.gstatic.com/generate_204")).append("\n");
            }
            b.append("\n204 = teste normalmente bem-sucedido. 3xx pode indicar redirecionamento. ")
             .append("Falha não identifica, sozinha, o motivo do bloqueio.");
            final String result = b.toString();
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                testResults.setText(result);
                testButton.setEnabled(true);
                testButton.setText("Testar internet");
                note("Teste de conectividade finalizado.");
            });
        });
    }

    private String probe(Network n, String urlText) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlText);
            URLConnection raw = n != null ? n.openConnection(url) : url.openConnection();
            connection = (HttpURLConnection) raw;
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "RedeClara-Diagnostico/1.0");
            int response = connection.getResponseCode();
            if (response == 204) return "204 - conectado";
            if (response >= 300 && response <= 399) return response + " - redirecionamento / portal possível";
            return response + " - resposta diferente do esperado";
        } catch (IOException e) {
            return "falhou (" + simpleError(e) + ")";
        } catch (RuntimeException e) {
            return "indisponível (" + simpleError(e) + ")";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void saveSnapshot() {
        JSONObject snapshot = captureSnapshot();
        prefs.edit().putString(KEY_SNAPSHOT, snapshot.toString()).apply();
        comparisonResults.setText("Estado de referência guardado em " + snapshot.optString("Momento")
                + ".\n\nDepois de uma mudança, toque em Comparar.");
        note("Referência de rede salva para comparação futura.");
        toast("Referência guardada neste celular");
    }

    private void compareSnapshot() {
        String stored = prefs.getString(KEY_SNAPSHOT, null);
        if (stored == null) {
            comparisonResults.setText("Ainda não existe referência. Toque em Guardar estado primeiro.");
            return;
        }
        try {
            JSONObject old = new JSONObject(stored);
            JSONObject now = captureSnapshot();
            String[] keys = {"Rede padrão", "Wi-Fi conectado", "Wi-Fi validado", "Portal identificado",
                    "VPN ativa", "IP Wi-Fi", "Gateway", "DNS", "DNS privado", "MTU"};
            StringBuilder diff = new StringBuilder();
            diff.append("Comparação com ").append(old.optString("Momento")).append("\n\n");
            int changed = 0;
            for (String key : keys) {
                String before = old.optString(key, "—");
                String after = now.optString(key, "—");
                if (!before.equals(after)) {
                    changed++;
                    diff.append("ALTEROU: ").append(key).append("\n")
                        .append("  antes: ").append(before).append("\n")
                        .append("  agora: ").append(after).append("\n\n");
                }
            }
            if (changed == 0) diff.append("Nenhuma diferença detectada nos dados acessíveis ao aplicativo.\n");
            diff.append("\nCampos modificados: ").append(changed)
                .append(".\nO limite de uso registrado pelo servidor não é visível aqui.");
            comparisonResults.setText(diff.toString());
            note("Comparação concluída: " + changed + " campo(s) alterado(s).");
        } catch (JSONException e) {
            comparisonResults.setText("A referência está inválida. Guarde outra referência.");
        }
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (RuntimeException ignored) { toast("Configuração não disponível neste dispositivo"); }
        }
    }

    private void shareReport() {
        String message = "REDECLARA 1.0 - Relatório local\n" + stamp() + "\n\n"
                + formatDetails(captureSnapshot()) + "\n\nÚLTIMO TESTE\n"
                + testResults.getText() + "\n\nHISTÓRICO\n" + prefs.getString(KEY_HISTORY, "")
                + "\n\nEste relatório não inclui MAC, senhas nem cookies. "
                + "Pode conter endereços IP privados e DNS: revise antes de compartilhar.";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Relatório RedeClara");
        send.putExtra(Intent.EXTRA_TEXT, message);
        startActivity(Intent.createChooser(send, "Compartilhar relatório"));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar histórico do RedeClara?")
                .setMessage("Isso apaga apenas os registros e a referência guardados por este aplicativo. Não altera a rede nem a cota do portal.")
                .setNegativeButton("Cancelar", (d, w) -> d.dismiss())
                .setPositiveButton("Limpar", (d, w) -> {
                    prefs.edit().remove(KEY_HISTORY).remove(KEY_SNAPSHOT).apply();
                    comparisonResults.setText("Nenhuma referência guardada.");
                    refreshHistory();
                    toast("Histórico local limpo");
                }).show();
    }

    private void note(String text) {
        String current = prefs.getString(KEY_HISTORY, "");
        String updated = "[" + stamp() + "] " + text + "\n" + current;
        if (updated.length() > MAX_HISTORY_CHARS) updated = updated.substring(0, MAX_HISTORY_CHARS);
        prefs.edit().putString(KEY_HISTORY, updated).apply();
        refreshHistory();
    }
    private void refreshHistory() {
        String entries = prefs.getString(KEY_HISTORY, "");
        history.setText(entries.isEmpty() ? "Nenhum registro por enquanto." : entries);
    }
    private static String boolLabel(boolean yes) { return yes ? "Sim" : "Não"; }
    private static String join(List<String> strings) {
        StringBuilder out = new StringBuilder();
        for (String s : strings) {
            if (out.length() != 0) out.append(", ");
            out.append(s);
        }
        return out.toString();
    }
    private static String simpleError(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return t.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ');
        return m.length() <= 90 ? m : m.substring(0, 90);
    }
    private static String stamp() {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
