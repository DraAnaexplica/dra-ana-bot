package com.draana.bot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import org.postgresql.ds.PGSimpleDataSource;
import okhttp3.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class App {
    private static final String TOKEN = System.getenv("TELEGRAM_TOKEN");
    private static final String DATABASE_URL = System.getenv("DATABASE_URL");
    private static final String OPENROUTER_API_KEY = System.getenv("OPENROUTER_API_KEY");
    private static final OkHttpClient client = new OkHttpClient();

    public static void main(String[] args) {
        // Verifica se as variáveis de ambiente estão definidas
        if (TOKEN == null || DATABASE_URL == null || OPENROUTER_API_KEY == null) {
            System.err.println("Erro: Uma ou mais variáveis de ambiente não estão definidas.");
            return;
        }

        TelegramBot bot = new TelegramBot(TOKEN);
        PGSimpleDataSource dataSource = configureDataSource();

        initDatabase(dataSource);

        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                if (update.message() != null && update.message().text() != null) {
                    String chatId = update.message().chat().id().toString();
                    String userId = update.message().from().id().toString();
                    String username = update.message().from().username() != null ? update.message().from().username() : "unknown";
                    String text = update.message().text();

                    String response = processMessage(text);
                    sendMessage(bot, chatId, response);
                    saveInteraction(dataSource, userId, username, text, response);
                }
            }
            return 1; // Substituído UpdatesListener.CONFIRM por 1
        });
    }

    private static PGSimpleDataSource configureDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        try {
            URI dbUri = new URI(DATABASE_URL.replace("postgres://", "postgresql://"));
            String username = dbUri.getUserInfo().split(":")[0];
            String password = dbUri.getUserInfo().split(":")[1];
            String host = dbUri.getHost();
            int port = dbUri.getPort();
            String database = dbUri.getPath().substring(1); // Remove a barra inicial

            dataSource.setServerNames(new String[]{host});
            dataSource.setPortNumbers(new int[]{port != -1 ? port : 5432}); // Porta padrão do PostgreSQL é 5432
            dataSource.setDatabaseName(database);
            dataSource.setUser(username);
            dataSource.setPassword(password);
            dataSource.setSslMode("require"); // Render geralmente exige SSL
        } catch (URISyntaxException e) {
            System.err.println("Erro ao parsear a URL do banco de dados: " + e.getMessage());
            throw new RuntimeException(e);
        }
        return dataSource;
    }

    private static void initDatabase(PGSimpleDataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "CREATE TABLE IF NOT EXISTS interactions (" +
                    "id SERIAL PRIMARY KEY, " +
                    "user_id VARCHAR(255) NOT NULL, " +
                    "username VARCHAR(255), " +
                    "message TEXT NOT NULL, " +
                    "response TEXT, " +
                    "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.execute();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static String processMessage(String text) {
        if (text.equals("/start")) {
            return "Olá! Sou a Dra. Ana, sua assistente médica. Envie sua dúvida!";
        } else if (text.equals("/help")) {
            return "Comandos:\n/start - Iniciar\n/help - Ajuda\nEnvie uma dúvida médica para uma resposta.";
        } else {
            return getOpenRouterResponse(text);
        }
    }

    private static String getOpenRouterResponse(String prompt) {
        try {
            String url = "https://openrouter.ai/api/v1/chat/completions";
            String json = "{ \"model\": \"mistralai/mixtral-8x7b-instruct\", \"messages\": [{\"role\": \"user\", \"content\": \"Você é a Dra. Ana, uma assistente médica. Responda de forma clara e profissional: " + prompt + "\"}] }";

            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Authorization", "Bearer " + OPENROUTER_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            String responseBody = response.body().string();
            return extractResponse(responseBody);
        } catch (IOException e) {
            e.printStackTrace();
            return "Desculpe, não consegui processar sua dúvida. Tente novamente.";
        }
    }

    private static String extractResponse(String responseBody) {
        // Extrai a resposta do JSON (simplificado)
        int contentStart = responseBody.indexOf("\"content\":\"") + 11;
        int contentEnd = responseBody.indexOf("\"", contentStart);
        return responseBody.substring(contentStart, contentEnd).replace("\\n", "\n");
    }

    private static void sendMessage(TelegramBot bot, String chatId, String text) {
        SendMessage message = new SendMessage(chatId, text);
        bot.execute(message);
    }

    private static void saveInteraction(PGSimpleDataSource dataSource, String userId, String username, String message, String response) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO interactions (user_id, username, message, response) VALUES (?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, username);
                stmt.setString(3, message);
                stmt.setString(4, response);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}