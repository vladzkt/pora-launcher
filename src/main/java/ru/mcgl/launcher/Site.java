package ru.mcgl.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Разговор с сайтом: вход по учётной записи и список файлов сборки.
 *
 * Пароль отсюда никуда не сохраняется. В обмен на него сайт даёт разовый ключ, который живёт
 * пять минут и тратится на входе в игру, - его и передаём игре, а пароль забываем.
 *
 * Сеть намеренно на HttpURLConnection, а не на новом HttpClient: тот поднимает внутреннее
 * соединение через петлю, и на машинах, где антивирус её запрещает, просто не создаётся.
 * Проверено на машине владельца: новый клиент падал с «Unable to establish loopback connection».
 */
public final class Site {

	public static final String BASE = "https://porakopatb.com";
	private static final String AGENT = "PoraKopatb-Launcher/1.0";

	/** Кто вошёл: ник, разовый ключ для игры и адрес скина. */
	public record Account(String nick, String token, String skin) {
	}

	/** Один файл сборки: куда положить, сколько весит и какая у него сумма. */
	public record PackFile(String path, long size, String sha1) {
		public String url() {
			return BASE + "/pack/" + path;
		}
	}

	/** Что игрок должен иметь у себя, чтобы зайти на сервер. */
	public record Pack(String version, String minecraft, String fabric, List<PackFile> files) {
	}

	private Site() {
	}

	private static HttpURLConnection open(String url, int timeoutMs) throws IOException {
		HttpURLConnection link = (HttpURLConnection) URI.create(url).toURL().openConnection();
		link.setConnectTimeout(15000);
		link.setReadTimeout(timeoutMs);
		link.setRequestProperty("User-Agent", AGENT);
		link.setInstanceFollowRedirects(true);
		return link;
	}

	public static Account login(String nick, String password) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("nick", nick);
		body.addProperty("password", password);

		HttpURLConnection link = open(BASE + "/api/launcher/login", 20000);
		link.setRequestMethod("POST");
		link.setDoOutput(true);
		link.setRequestProperty("content-type", "application/json");
		try (OutputStream out = link.getOutputStream()) {
			out.write(body.toString().getBytes(StandardCharsets.UTF_8));
		}
		// Сайт отвечает разбираемым JSON и на отказ, поэтому читаем оба потока.
		String answer = read(link.getResponseCode() < 400 ? link.getInputStream() : link.getErrorStream());
		JsonObject json = JsonParser.parseString(answer).getAsJsonObject();
		if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
			throw new IOException(reason(json.has("error") ? json.get("error").getAsString() : ""));
		}
		return new Account(json.get("nick").getAsString(), json.get("token").getAsString(),
				json.has("skin") ? json.get("skin").getAsString() : "");
	}

	/** Ошибку сайта показываем по-человечески: код для нас, строка для игрока. */
	private static String reason(String code) {
		return switch (code) {
			case "bad_credentials" -> "Ник или пароль не подошли.";
			case "banned" -> "Эта учётная запись заблокирована.";
			case "too_often" -> "Слишком много попыток. Подожди несколько минут.";
			default -> "Сайт ответил отказом. Попробуй позже.";
		};
	}

	public static Pack pack() throws IOException {
		JsonObject json = JsonParser.parseString(text(BASE + "/api/launcher/manifest")).getAsJsonObject();
		List<PackFile> files = new ArrayList<>();
		JsonArray list = json.getAsJsonArray("files");
		for (int i = 0; i < list.size(); i++) {
			JsonObject f = list.get(i).getAsJsonObject();
			files.add(new PackFile(f.get("path").getAsString(), f.get("size").getAsLong(),
					f.get("sha1").getAsString()));
		}
		return new Pack(json.get("version").getAsString(), json.get("minecraft").getAsString(),
				json.get("fabric").getAsString(), files);
	}

	public static String text(String url) throws IOException {
		HttpURLConnection link = open(url, 120000);
		int code = link.getResponseCode();
		if (code != 200) {
			throw new IOException("Не ответил (" + code + "): " + url);
		}
		return read(link.getInputStream());
	}

	/** Скачать в файл, создав папки по дороге. Возвращает, сколько байт пришло. */
	public static long download(String url, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		Path temp = to.resolveSibling(to.getFileName() + ".part");
		HttpURLConnection link = open(url, 600000);
		int code = link.getResponseCode();
		if (code != 200) {
			throw new IOException("Не скачалось (" + code + "): " + url);
		}
		try (InputStream in = link.getInputStream()) {
			Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
		}
		// Кладём на место одним движением: прерванная загрузка не оставит битый файл под нужным именем.
		Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING);
		return Files.size(to);
	}

	private static String read(InputStream in) throws IOException {
		if (in == null) {
			return "{}";
		}
		try (InputStream stream = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[1 << 14];
			int n;
			while ((n = stream.read(buffer)) > 0) {
				out.write(buffer, 0, n);
			}
			return out.toString(StandardCharsets.UTF_8);
		}
	}
}
