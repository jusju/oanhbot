package engine;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
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
        // Sallii myös desimaalit. Jos tulos on kokonaisluku, palautetaan ilman .0-loppua.
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

    public String etsiRuokalista() {
        String ruokainfo = "";
        String paivaTanaan = LocalDate.now().format(DateTimeFormatter.ofPattern("d.M.yyyy"));
        String viikonpaiva = LocalDate.now().getDayOfWeek().toString();
        ruokainfo = viikonpaiva + " " + paivaTanaan + " ";

        try {
            URL url = new URL("https://hhapp.info/api/amica/pasila/fi");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.connect();

            Scanner sc = new Scanner(url.openStream());
            String inline = sc.useDelimiter("\\A").next();
            sc.close();

            JSONParser parse = new JSONParser();
            JSONObject result = (JSONObject) parse.parse(inline);
            JSONArray menus = (JSONArray) result.get("LunchMenus");

            for (Object o : menus) {
                JSONObject dayInfo = (JSONObject) o;
                String date = (String) dayInfo.get("Date");
                if (date.equals(paivaTanaan)) {
                    JSONArray setMenus = (JSONArray) dayInfo.get("SetMenus");
                    for (Object m : setMenus) {
                        JSONObject mealInfo = (JSONObject) m;
                        JSONArray meals = (JSONArray) mealInfo.get("Meals");
                        for (Object meal : meals) {
                            String name = (String) ((JSONObject) meal).get("Name");
                            ruokainfo += "\n - " + name;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            ruokainfo = "Virhe ruokalistan hakemisessa.";
        }

        return ruokainfo;
    }

    public String etsiSaa() {
        try {
            String kaupunki = "Helsinki";
            String apiKey = "a8720cf3a65bd981b2fecc6381cd729e";
            String urlString = "https://api.openweathermap.org/data/2.5/weather?q=" + kaupunki + "&APPID=" + apiKey + "&units=metric";

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

            return "Outside at " + kaupunki + " it is now " + temp + "°C. Wind speed: " + speed + " m/s.\nToday is " + formattedDate + " at " + timeNow + ".";

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
