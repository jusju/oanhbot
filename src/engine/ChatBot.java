package engine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Iterator;
import java.util.Locale;
import java.util.Scanner;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import org.json.simple.*;
import org.json.simple.parser.*;

public class ChatBot extends TelegramLongPollingBot {

    private static final String FEED_URL = "https://www.compass-group.fi/menuapi/feed/json?costNumber=0083&language=fi";


    public String etsiRuokalista() {
        ZoneId tz = ZoneId.of("Europe/Helsinki");
        LocalDate today = LocalDate.now(tz);

        String paivaTanaanFI = today.format(DateTimeFormatter.ofPattern("d.M.yyyy"));
        String viikonpaivaFI = today.getDayOfWeek().getDisplayName(TextStyle.FULL, new Locale("fi"));

        StringBuilder ruokainfo = new StringBuilder();
        ruokainfo.append(viikonpaivaFI).append(" ").append(paivaTanaanFI).append(" ");

        try {
            // HTTP GET
            URL url = new URL(FEED_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null)
                throw new RuntimeException("HTTP-virhe: " + status);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null)
                    sb.append(line);
            } finally {
                conn.disconnect();
            }

            // JSON parse
            JSONParser parser = new JSONParser();
            JSONObject root = (JSONObject) parser.parse(sb.toString());

            // Uusi muoto (Compass): "MenusForDays" — fallback vanhaan: "LunchMenus"
            JSONArray days = (JSONArray) root.get("MenusForDays");
            if (days == null) {
                days = (JSONArray) root.get("LunchMenus");
            }
            if (days == null) {
                return "Virhe: tuntematon JSON-muoto (puuttuu MenusForDays/LunchMenus).";
            }

            boolean foundToday = false;

            for (Object dayObj : days) {
                if (!(dayObj instanceof JSONObject))
                    continue;
                JSONObject day = (JSONObject) dayObj;

                String dateStr = asString(day.get("Date"));
                LocalDate date = parseDateFlexible(dateStr, tz);
                if (date == null || !date.equals(today))
                    continue;

                foundToday = true;

                JSONArray setMenus = (JSONArray) day.get("SetMenus");
                if (setMenus == null)
                    continue;

                for (Object smObj : setMenus) {
                    if (!(smObj instanceof JSONObject))
                        continue;
                    JSONObject sm = (JSONObject) smObj;

                    // Uudempi: "Components": [ "Lohikeitto (L,G)", "Ruisleipä", ... ]
                    JSONArray components = (JSONArray) sm.get("Components");
                    if (components != null && !components.isEmpty()) {
                        for (Object comp : components) {
                            if (comp != null)
                                ruokainfo.append("\n - ").append(comp.toString());
                        }
                        continue;
                    }

                    // Vanhempi: "Meals": [ { "Name": "..." }, ... ]
                    JSONArray meals = (JSONArray) sm.get("Meals");
                    if (meals != null && !meals.isEmpty()) {
                        for (Object mealObj : meals) {
                            if (mealObj instanceof JSONObject) {
                                String name = asString(((JSONObject) mealObj).get("Name"));
                                if (name != null && !name.isEmpty()) {
                                    ruokainfo.append("\n - ").append(name);
                                }
                            }
                        }
                        continue;
                    }

                    // Viimesijainen varmistus: käytä setin "Name"-kenttää, jos muuta ei ole
                    String setName = asString(sm.get("Name"));
                    if (setName != null && !setName.isEmpty()) {
                        ruokainfo.append("\n - ").append(setName);
                    }
                }
                break; // tämän päivän löysimme, ei jatketa
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

    private static String asString(Object o) {
        return (o == null) ? null : o.toString();
    }

    private static LocalDate parseDateFlexible(String s, ZoneId tz) {
        if (s == null)
            return null;
        // Yleisimmät muotoilut: "d.M.yyyy" (vanha Amica) ja "yyyy-MM-dd" (Compass-feed)
        String[] patterns = { "d.M.yyyy", "dd.MM.yyyy", "yyyy-MM-dd" };
        for (String p : patterns) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p).withLocale(Locale.ROOT);
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignore) {
            }
        }
        // Viimeinen yritys: suora ISO-parse
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ignore) {
        }
        return null;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            String response = sanoTakaisin(text);

            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(update.getMessage().getChatId()));
            message.setText(response);

            try {
                execute(message);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    public String sanoTakaisin(String userMessage) {
        // UUSI: /add <a> <b> -komento
        // Sallii myös desimaalit. Jos tulos on kokonaisluku, palautetaan ilman
        // .0-loppua.
        if (userMessage != null && userMessage.trim().startsWith("/add")) {
            String[] parts = userMessage.trim().split("\\s+");
            if (parts.length == 3) {
                try {
                    // Yritä kokonaisluvuilla ensin
                    long a = Long.parseLong(parts[1]);
                    long b = Long.parseLong(parts[2]);
                    return String.valueOf(a + b);
                } catch (NumberFormatException nfeInt) {
                    try {
                        double a = Double.parseDouble(parts[1]);
                        double b = Double.parseDouble(parts[2]);
                        double sum = a + b;
                        if (Math.floor(sum) == sum) {
                            return String.valueOf((long) sum);
                        } else {
                            return String.valueOf(sum);
                        }
                    } catch (NumberFormatException nfeDbl) {
                        return "Usage: /add <number> <number>, e.g. /add 5 2";
                    }
                }
            } else {
                return "Usage: /add <number> <number>, e.g. /add 5 2";
            }
        }

        if (userMessage.equals("/onko ohjelmointi kivaa?")) {
            return "Kyllä. Siitä saa kicksejä!";
        } else if (userMessage.equals("/who will fetch pauline today?")) {
            return "I can tell you that when that feature is implemented.";
        } else if (userMessage.equals("/when did jukka pay?")) {
            return "I can tell you that when that feature is implemented.";
        } else if (userMessage.equals("/weather")) {
            return etsiSaa();
        } else if (userMessage.equals("/foodmenu")) {
            return etsiRuokalista();
        } else if (userMessage.equals("/time")) {
            return etsiJukanMaksut();
        } else {
            return "I do not understand.";
        }
    }

    public String etsiJukanMaksut() {
        LocalDateTime now = LocalDateTime.now();
        return "Aika nyt: " + now.toString();
    }

   

    public String etsiSaa() {
        try {
            String kaupunki = "Helsinki";
            String apiKey = "a8720cf3a65bd981b2fecc6381cd729e";
            String urlString = "https://api.openweathermap.org/data/2.5/weather?q=" + kaupunki + "&APPID=" + apiKey
                    + "&units=metric";

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.connect();

            Scanner sc = new Scanner(url.openStream());
            String inline = sc.useDelimiter("\\A").next();
            sc.close();

            JSONParser parse = new JSONParser();
            JSONObject result = (JSONObject) parse.parse(inline);
            JSONObject main = (JSONObject) result.get("main");
            JSONObject wind = (JSONObject) result.get("wind");

            double temp = (Double) main.get("temp");
            double speed = (Double) wind.get("speed");

            LocalDate today = LocalDate.now();
            String formattedDate = today.format(DateTimeFormatter.ofPattern("EEEE dd.MM.yyyy"));
            String timeNow = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

            return "Outside at " + kaupunki + " it is now " + temp + "°C. Wind speed: " + speed + " m/s.\nToday is "
                    + formattedDate + " at " + timeNow + ".";

        } catch (Exception e) {
            e.printStackTrace();
            return "Could not retrieve weather information.";
        }
    }

    @Override
    public String getBotUsername() {
        return "inarabot";
    }

    @Override
    public String getBotToken() {
        return "8124717596:AAG8lIlRzN7YW2iNIPegKjQb00da0iLkX2A";
    }

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new ChatBot());
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}
