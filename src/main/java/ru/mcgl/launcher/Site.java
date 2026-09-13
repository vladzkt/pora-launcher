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
import com.google.gson.JsonElement;
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

	/** Кто вошёл: ник, разовый ключ для игры, адрес скина и ключ «запомнить», если просили. */
	public record Account(String nick, String token, String skin, String device) {
	}

	/** Один файл сборки: куда положить, сколько весит и какая у него сумма. */
	public record PackFile(String path, long size, String sha1) {
		public String url() {
			return BASE + "/pack/" + path;
		}
	}

	/** Одна новость с сайта. */
	public record News(String title, String url) {
	}

	/** Что показать в окне: новости, кто в игре и куда ведут кнопки. */
	public record Home(java.util.List<News> news, boolean online, java.util.List<String> players,
			String register, String wiki, String map, String forum) {
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

	/**
	 * Вход по нику и паролю. Если {@code remember} - сайт вернёт ещё и долгий ключ для этой
	 * машины, чтобы в следующий раз пароль не спрашивать.
	 */
	public static Account login(String nick, String password, boolean remember) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("nick", nick);
		body.addProperty("password", password);
		if (remember) {
			body.addProperty("remember", true);
		}
		return ask(body);
	}

	/**
	 * Вход по сохранённому ключу. Пароль на диске не лежит: у нас только этот ключ, он годится
	 * лишь для лаунчера и пропадает, когда игрок меняет пароль.
	 */
	public static Account loginSaved(String device) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("device", device);
		return ask(body);
	}

	private static Account ask(JsonObject body) throws IOException {
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
				json.has("skin") ? json.get("skin").getAsString() : "",
				json.has("device") ? json.get("device").getAsString() : "");
	}

	/** Ошибку сайта показываем по-человечески: код для нас, строка для игрока. */
	private static String reason(String code) {
		return switch (code) {
			case "bad_credentials" -> "Ник или пароль не подошли.";
			case "banned" -> "Эта учётная запись заблокирована.";
			case "too_often" -> "Слишком много попыток. Подожди несколько минут.";
			case "device_gone" -> "Сохранённый вход больше не годится - введи пароль.";
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

	/**
	 * Новости, онлайн и ссылки - одним запросом. Окно без этого работает, поэтому ждём недолго
	 * и на любую осечку возвращаем пустое.
	 */
	public static Home home() {
		try {
			HttpURLConnection link = open(BASE + "/api/launcher/home", 8000);
			if (link.getResponseCode() != 200) {
				return null;
			}
			JsonObject json = JsonParser.parseString(read(link.getInputStream())).getAsJsonObject();
			List<News> news = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("news")) {
				JsonObject one = e.getAsJsonObject();
				news.add(new News(one.get("title").getAsString(), one.get("url").getAsString()));
			}
			List<String> players = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("players")) {
				players.add(e.getAsString());
			}
			JsonObject links = json.getAsJsonObject("links");
			return new Home(news, json.get("online").getAsBoolean(), players,
					links.get("register").getAsString(), links.get("wiki").getAsString(),
					links.get("map").getAsString(), links.get("forum").getAsString());
		} catch (Exception quiet) {
			return null;
		}
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
