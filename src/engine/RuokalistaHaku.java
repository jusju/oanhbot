package engine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class RuokalistaHaku {

    // Pasilan Pääraide / Opetustalo
    private static final String FEED_URL =
            "https://www.compass-group.fi/menuapi/feed/json?costNumber=0083&language=fi";

    public String etsiRuokalista() {
        ZoneId tz = ZoneId.of("Europe/Helsinki");
        LocalDate today = LocalDate.now(tz);

        String paivaTanaanFI = today.format(DateTimeFormatter.ofPattern("d.M.yyyy"));
        String viikonpaivaFI = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("fi", "FI"));

        StringBuilder ruokainfo = new StringBuilder();
        ruokainfo.append(viikonpaivaFI).append(" ").append(paivaTanaanFI).append(" ");

        try {
            // HTTP GET
            URL url = new URL(FEED_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; PaaRaideMenuBot/1.0)");
            conn.setRequestProperty("Accept-Encoding", "gzip"); // joillain saiteilla pakkaus on päällä
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) throw new RuntimeException("HTTP-virhe: " + status);

            // Purku gzipille tarvittaessa
            String contentEncoding = conn.getContentEncoding();
            if (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip")) {
                is = new GZIPInputStream(is);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            } finally {
                conn.disconnect();
            }

            String json = stripBom(sb.toString()).trim();
            if (json.isEmpty()) return "Virhe: tyhjä vastaus palvelimelta.";

            // JSON parse
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(json);

            if (!(parsed instanceof JSONObject)) {
                // tulosta pari sataa ekaa merkkiä debugiin
                return "Virhe: JSON-juuri ei ole objekti. Alku: " + json.substring(0, Math.min(200, json.length()));
            }
            JSONObject root = (JSONObject) parsed;

            // Uusi muoto: "MenusForDays" -> päivät -> "SetMenus" -> "Components"
            JSONArray days = (JSONArray) root.get("MenusForDays");
            if (days == null) {
                // Vanhat/poikkeavat
                days = (JSONArray) root.get("LunchMenus");
            }
            if (days == null) {
                return "Virhe: tuntematon JSON-muoto (puuttuu MenusForDays/LunchMenus). Raaka JSON alku: "
                        + json.substring(0, Math.min(200, json.length()));
            }

            boolean foundToday = false;

            for (Object dayObj : days) {
                if (!(dayObj instanceof JSONObject)) continue;
                JSONObject day = (JSONObject) dayObj;

                String dateStr = asString(day.get("Date"));
                LocalDate date = parseDateFlexible(dateStr, tz);
                if (date == null || !date.equals(today)) continue;

                foundToday = true;

                JSONArray setMenus = (JSONArray) day.get("SetMenus");
                if (setMenus == null) continue;

                for (Object smObj : setMenus) {
                    if (!(smObj instanceof JSONObject)) continue;
                    JSONObject sm = (JSONObject) smObj;

                    // Uudempi: Components: [ "Lohikeitto (L,G)", ... ]
                    JSONArray components = (JSONArray) sm.get("Components");
                    if (components != null && !components.isEmpty()) {
                        for (Object comp : components) {
                            if (comp != null) ruokainfo.append("\n - ").append(comp.toString());
                        }
                        continue;
                    }

                    // Vanhempi rakenne: Meals -> { Name }
                    JSONArray meals = (JSONArray) sm.get("Meals");
                    if (meals != null && !meals.isEmpty()) {
                        for (Object mealObj : meals) {
                            if (mealObj instanceof JSONObject) {
                                String name = asString(((JSONObject) mealObj).get("Name"));
                                if (name != null && !name.isEmpty()) {
                                    ruokainfo.append("\n - ").append(name);
                                }
                            } else if (mealObj != null) {
                                ruokainfo.append("\n - ").append(mealObj.toString());
                            }
                        }
                        continue;
                    }

                    // Viimesijainen: käytä setin nimeä
                    String setName = asString(sm.get("Name"));
                    if (setName != null && !setName.isEmpty()) {
                        ruokainfo.append("\n - ").append(setName);
                    }
                }
                break; // tämän päivän rivi käsitelty
            }

            if (!foundToday) {
                ruokainfo.append("\nEi ruokalistaa tälle päivälle.");
            }

            return ruokainfo.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "Virhe ruokalistan hakemisessa: " + e.getMessage();
        }
    }

    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    private static String asString(Object o) {
        return (o == null) ? null : o.toString();
    }

    private static LocalDate parseDateFlexible(String s, ZoneId tz) {
        if (s == null) return null;
        String t = s.trim();

        // Jos muodossa yyyy-MM-ddTHH:mm:ss+ZZ: irrota vain päivä
        int tPos = t.indexOf('T');
        if (tPos > 0) t = t.substring(0, tPos);

        // Yleisimmät: d.M.yyyy | dd.MM.yyyy | yyyy-MM-dd
        String[] patterns = { "d.M.yyyy", "dd.MM.yyyy", "yyyy-MM-dd" };
        for (String p : patterns) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p).withLocale(Locale.ROOT);
                return LocalDate.parse(t, f);
            } catch (DateTimeParseException ignore) {}
        }
        // Viimeinen yritys ISO:lle
        try { return LocalDate.parse(t); } catch (DateTimeParseException ignore) {}
        return null;
    }

    // Pika-ajotesti
    public static void main(String[] args) {
        System.out.println(new RuokalistaHaku().etsiRuokalista());
    }
}
