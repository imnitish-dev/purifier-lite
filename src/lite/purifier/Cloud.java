package lite.purifier;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.json.*;

/** Xiaomi cloud client for the Air Purifier 4 Lite (zhimi.airp.rmb1). Shared by the app and the widget. */
class Cloud {
    static final String[] SERVERS = {"in", "i2", "sg", "de", "us", "ru", "tw", "cn"};
    // {siid, piid} from the MIoT spec of zhimi.airp.rmb1
    static final int[][] PROPS = {{2, 1}, {2, 4}, {9, 11}, {3, 4}, {3, 1}, {3, 7}, {4, 1}, {4, 4}, {8, 1}, {13, 2}, {6, 1}, {2, 2}};
    static final int ON = 0, MODE = 1, FAV = 2, PM = 3, HUM = 4, TEMP = 5, FILTER = 6, DAYS = 7, LOCK = 8, BRIGHT = 9, BEEP = 10, FAULT = 11;

    static class AuthError extends IOException {}

    final Context ctx;
    final SharedPreferences sp;
    final SecureRandom rnd = new SecureRandom();

    Cloud(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("s", 0);
        if (!sp.contains("deviceId")) sp.edit().putString("deviceId", Long.toHexString(rnd.nextLong())).apply();
    }

    boolean loggedIn() { return sp.contains("token"); }
    void logout() { sp.edit().remove("token").remove("ssecurity").remove("userId").remove("did").apply(); }
    static boolean isPurifier(JSONObject d) { return d.optString("model").equals("zhimi.airp.rmb1"); }
    // Xiaomi's own PM2.5 bands for the 4 Lite's light ring (user manual): green 0-20, yellow 21-35, orange 36-55, red >55
    static int pmColor(int pm) { return pm <= 20 ? 0xFF3DDC97 : pm <= 35 ? 0xFFF2D43D : pm <= 55 ? 0xFFF59342 : 0xFFEF5350; }
    static String pmLabel(int pm) { return pm <= 20 ? "Excellent" : pm <= 35 ? "Good" : pm <= 55 ? "Moderate" : "Poor"; }
    static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    /** Device list; if the saved server has none, tries every region and keeps the one that has devices. */
    JSONArray devices() throws Exception {
        JSONArray list = deviceList();
        if (list.length() == 0) {
            String orig = sp.getString("server", "in");
            for (String s : SERVERS) {
                if (s.equals(orig)) continue;
                sp.edit().putString("server", s).commit();
                try { list = deviceList(); } catch (AuthError e) { throw e; } catch (Exception e) { Log.w("Purifier", s + ": " + e); }
                Log.i("Purifier", s + ": " + list.length() + " devices");
                if (list.length() > 0) break;
            }
            if (list.length() == 0) sp.edit().putString("server", orig).commit();
        }
        for (int i = 0; i < list.length(); i++)
            if (isPurifier(list.getJSONObject(i))) { sp.edit().putString("did", list.getJSONObject(i).getString("did")).apply(); break; }
        return list;
    }

    JSONArray deviceList() throws Exception {
        return api("/home/device_list", new JSONObject().put("getVirtualModel", false).put("getHuamiDevices", 0))
                .getJSONObject("result").getJSONArray("list");
    }

    Object[] getProps(String did) throws Exception {
        // Ask the purifier to keep measuring/reporting air quality even while the fan is off.
        try { setRaw(did, new int[]{11, 4}, 300); } catch (AuthError e) { throw e; } catch (Exception e) { Log.w("Purifier", "heartbeat: " + e); }
        JSONArray params = new JSONArray();
        for (int[] p : PROPS) params.put(new JSONObject().put("did", did).put("siid", p[0]).put("piid", p[1]));
        JSONArray res = api("/miotspec/prop/get", new JSONObject().put("params", params)).getJSONArray("result");
        Object[] out = new Object[PROPS.length];
        for (int i = 0; i < res.length(); i++) {
            JSONObject r = res.getJSONObject(i);
            for (int k = 0; k < PROPS.length; k++)
                if (PROPS[k][0] == r.optInt("siid") && PROPS[k][1] == r.optInt("piid")) out[k] = r.opt("value");
        }
        for (int k = 0; k < out.length; k++) if (out[k] == null) out[k] = "–";
        return out;
    }

    Object getProp(String did, int siid, int piid) throws Exception {
        JSONArray params = new JSONArray().put(new JSONObject().put("did", did).put("siid", siid).put("piid", piid));
        return api("/miotspec/prop/get", new JSONObject().put("params", params)).getJSONArray("result").getJSONObject(0).opt("value");
    }

    void setRaw(String did, int[] prop, Object value) throws Exception {
        JSONObject p = new JSONObject().put("did", did).put("siid", prop[0]).put("piid", prop[1]).put("value", value);
        api("/miotspec/prop/set", new JSONObject().put("params", new JSONArray().put(p)));
    }

    void login(String user, String pass, String server) throws Exception {
        String cookie = "sdkVersion=accountsdk-18.8.15; deviceId=" + sp.getString("deviceId", "") + "; userId=" + user;
        String sign = json(http("https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true", null, cookie, false)).optString("_sign");
        String form = "sid=xiaomiio&hash=" + hex(MessageDigest.getInstance("MD5").digest(pass.getBytes(StandardCharsets.UTF_8))).toUpperCase()
                + "&callback=" + enc("https://sts.api.io.mi.com/sts") + "&qs=" + enc("%3Fsid%3Dxiaomiio%26_json%3Dtrue")
                + "&user=" + enc(user) + "&_sign=" + enc(sign) + "&_json=true";
        JSONObject j = json(http("https://account.xiaomi.com/pass/serviceLoginAuth2", form, cookie, false));
        String ssecurity = j.optString("ssecurity");
        if (ssecurity.length() < 5) {
            String verify = j.optString("notificationUrl");
            if (!verify.isEmpty()) {
                throw new Exception("Xiaomi wants to verify this sign-in. Use the green \"Sign in on Xiaomi page\" button instead: it handles the code.");
            }
            throw new Exception(j.optString("desc", "Login failed (wrong email/password?)"));
        }
        finishLogin(j, server);
    }

    /**
     * Login done on Xiaomi's own web page (in-app WebView): it handles verification codes and captchas.
     * Its passToken lets us ask for the API session directly, with no password involved.
     */
    void loginWithPassToken(String userId, String passToken, String deviceId) throws Exception {
        if (deviceId != null) sp.edit().putString("deviceId", deviceId).apply();
        String cookie = "sdkVersion=accountsdk-18.8.15; deviceId=" + sp.getString("deviceId", "") + "; userId=" + userId + "; passToken=" + passToken;
        JSONObject j = json(http("https://account.xiaomi.com/pass/serviceLogin?sid=xiaomiio&_json=true", null, cookie, false));
        if (j.optString("ssecurity").length() < 5) throw new Exception(j.optString("desc", "Xiaomi did not accept the sign-in. Please try again."));
        finishLogin(j, "in");
    }

    void finishLogin(JSONObject j, String server) throws Exception {
        String ssecurity = j.getString("ssecurity");
        // Follow redirects by hand to catch the serviceToken cookie.
        String url = j.getString("location"), token = null;
        for (int i = 0; i < 5 && url != null && token == null; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua());
            c.getResponseCode();
            List<String> cookies = c.getHeaderFields().get("Set-Cookie");
            if (cookies != null) for (String s : cookies)
                if (s.startsWith("serviceToken=")) token = s.substring(13, s.indexOf(';') > 0 ? s.indexOf(';') : s.length());
            url = c.getHeaderField("Location");
            c.disconnect();
        }
        if (token == null) throw new Exception("Login failed: no service token");
        sp.edit().putString("token", token).putString("ssecurity", ssecurity)
                .putString("userId", String.valueOf(j.get("userId"))).putString("server", server).apply();
    }

    /** RC4-encrypted MIoT cloud call, same scheme the Xiaomi Home app uses. */
    JSONObject api(String path, JSONObject data) throws Exception {
        String server = sp.getString("server", "in");
        String base = server.equals("cn") ? "https://api.io.mi.com/app" : "https://" + server + ".api.io.mi.com/app";
        String ssec = sp.getString("ssecurity", "");
        byte[] nb = new byte[12];
        rnd.nextBytes(nb);
        ByteBuffer.wrap(nb, 8, 4).putInt((int) (System.currentTimeMillis() / 60000));
        String nonce = b64(nb);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(b64d(ssec)); sha.update(nb);
        String sn = b64(sha.digest());

        LinkedHashMap<String, String> p = new LinkedHashMap<>();
        p.put("data", data.toString());
        p.put("rc4_hash__", encSig(path, sn, p));
        for (Map.Entry<String, String> e : p.entrySet()) e.setValue(b64(rc4(sn, e.getValue().getBytes(StandardCharsets.UTF_8))));
        p.put("signature", encSig(path, sn, p));
        p.put("ssecurity", ssec);
        p.put("_nonce", nonce);
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) form.append(form.length() > 0 ? "&" : "").append(e.getKey()).append('=').append(enc(e.getValue()));

        String tok = sp.getString("token", "");
        String cookie = "userId=" + sp.getString("userId", "") + "; yetAnotherServiceToken=" + tok + "; serviceToken=" + tok
                + "; locale=en_IN; channel=MI_APP_STORE";
        String body = http(base + path, form.toString(), cookie, true);
        JSONObject r = new JSONObject(new String(rc4(sn, b64d(body)), StandardCharsets.UTF_8));
        if (r.optInt("code") != 0) throw new Exception(r.optString("message", "code " + r.optInt("code")));
        return r;
    }

    static String encSig(String path, String sn, Map<String, String> p) throws Exception {
        StringBuilder s = new StringBuilder("POST&").append(path);
        for (Map.Entry<String, String> e : p.entrySet()) s.append('&').append(e.getKey()).append('=').append(e.getValue());
        s.append('&').append(sn);
        return b64(MessageDigest.getInstance("SHA-1").digest(s.toString().getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] rc4(String key, byte[] in) throws Exception {
        Cipher c = Cipher.getInstance("ARC4");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(b64d(key), "ARC4"));
        c.update(new byte[1024]); // Xiaomi drops the first 1024 keystream bytes
        return c.doFinal(in);
    }

    String http(String url, String form, String cookie, boolean miot) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", ua());
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        if (miot) {
            c.setRequestProperty("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2");
            c.setRequestProperty("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4");
            c.setRequestProperty("Accept-Encoding", "identity");
        }
        if (form != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        if (code == 401 || code == 403) throw new AuthError();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; in != null && (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        if (code >= 400) throw new IOException("HTTP " + code);
        return out.toString("UTF-8");
    }

    String ua() { return "Android-7.1.1-1.0.0-ONEPLUS A3010-136-" + sp.getString("deviceId", "") + " APP/xiaomi.smarthome APPV/62830"; }
    static JSONObject json(String s) throws JSONException { return new JSONObject(s.replace("&&&START&&&", "")); }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { throw new RuntimeException(e); } }
    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    static byte[] b64d(String s) { return Base64.decode(s, Base64.DEFAULT); }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
}
