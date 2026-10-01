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

	public static final String MAIN = "https://porakopatb.com";
	/**
	 * Московское зеркало (29.09.2026): российские провайдеры режут трафик к Hetzner, где стоит
	 * сайт, - соединение открывается, первые ~16 КБ проходят, дальше тишина. Через этот адрес
	 * тот же сайт идёт посредником в Москве. Какой из двух брать, решает {@link #chooseRoute()}.
	 */
	public static final String RU = "https://ru.porakopatb.com";
	private static final int PROBE_BYTES = 64 * 1024;
	private static final int PROBE_MS = 6000;

	private static volatile String base = MAIN;

	/** Адрес сайта для этого запуска, без косой черты в конце. */
	public static String base() {
		return base;
	}

	public static boolean viaMoscow() {
		return RU.equals(base);
	}
	private static final String AGENT = "PoraKopatb-Launcher/1.0";

	/** Кто вошёл: ник, разовый ключ для игры, адрес скина и ключ «запомнить», если просили. */
	/**
	 * Учётная запись игрока и всё, что сайт выдал на этот вход.
	 *
	 * {@code packKey} - ключ от кладовой модов. Сборка лежит на диске зашифрованной, и без
	 * ключа её не прочитать; ключ выдаётся только вошедшему и нигде не сохраняется.
	 */
	public record Account(String nick, String token, String skin, String device, String packKey) {
	}

	/** Один файл сборки: куда положить, сколько весит и какая у него сумма. */
	public record PackFile(String path, long size, String sha1) {
		public String url() {
			return base + "/pack/" + path;
		}
	}

	/** Одна новость с сайта: заголовок, ссылка, когда вышла и первая строка текста. */
	public record News(String title, String url, long at, String snippet) {
	}

	/** Скин игрока на сайте - из него лаунчер вырезает лицо для строки «Сейчас в игре». */
	public static String skinUrl(String nick) {
		return base + "/skin/" + nick + ".png";
	}

	/**
	 * Событие из афиши сервера (01.10.2026): вождь огров, счастливый час, закупка города и прочее.
	 * {@code state} - soon (начнётся в {@code at}), live (идёт, до {@code until}; 0 - неизвестно
	 * до какого) или window (минута ещё не выбрана, будет между {@code at} и {@code until}).
	 * {@code when} - готовое «когда» по Киеву от сайта: для подсказки, а остаток лаунчер считает
	 * сам по своим часам.
	 */
	public record Soon(String id, String state, String title, String where, String when, long at, long until) {
	}

	/** Что показать в окне: новости, кто в игре, ближайшие события и куда ведут кнопки. */
	public record Home(java.util.List<News> news, boolean online, java.util.List<String> players,
			String register, String wiki, String map, String forum, java.util.List<Soon> soon) {
	}

	/** Что игрок должен иметь у себя, чтобы зайти на сервер. */
	public record Pack(String version, String minecraft, String fabric, List<PackFile> files) {
	}

	private Site() {
	}

	/**
	 * Основной адрес или зеркало: тянем 64 КБ {@code /api/probe}, первым - с того, что сработал в
	 * прошлый раз. Маленький запрос ничего не доказывает: режущий провайдер пропускает первые
	 * 16 КБ. Не дотянули ни с одного - остаёмся на прошлом выборе, дальше скажет сама загрузка.
	 */
	public static void chooseRoute() {
		Path remembered = Files2.home().resolve("route.txt");
		String last = "";
		try {
			last = Files.readString(remembered, StandardCharsets.UTF_8).trim();
		} catch (IOException none) {
			// первый запуск
		}
		String first = "ru".equals(last) ? RU : MAIN;
		String second = first.equals(MAIN) ? RU : MAIN;
		if (reaches(first)) {
			base = first;
		} else if (reaches(second)) {
			base = second;
		} else {
			base = first;
		}
		try {
			Files.createDirectories(remembered.getParent());
			Files.writeString(remembered, viaMoscow() ? "ru" : "main", StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// не записали - в следующий раз просто пробуем по порядку
		}
	}

	private static boolean reaches(String site) {
		long deadline = System.currentTimeMillis() + PROBE_MS;
		try {
			HttpURLConnection link = open(site + "/api/probe", PROBE_MS);
			if (link.getResponseCode() != 200) {
				return false;
			}
			byte[] buffer = new byte[8192];
			int total = 0;
			try (InputStream in = link.getInputStream()) {
				int n;
				while ((n = in.read(buffer)) > 0) {
					total += n;
					if (System.currentTimeMillis() > deadline) {
						return false;
					}
				}
			}
			return total >= PROBE_BYTES;
		} catch (IOException | RuntimeException unreachable) {
			return false;
		}
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
		// Отпечаток компьютера - хеш, не сам идентификатор (см. Machine). По нему сайт держит правило
		// «не больше двух аккаунтов на компьютер» - одинаково для входа по паролю и по ключу.
		body.addProperty("machine", Machine.id());
		HttpURLConnection link = open(base + "/api/launcher/login", 20000);
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
			throw new IOException(reason(json.has("error") ? json.get("error").getAsString() : "",
					json.has("scope") && json.get("scope").isJsonPrimitive() ? json.get("scope").getAsString() : ""));
		}
		return new Account(json.get("nick").getAsString(), json.get("token").getAsString(),
				json.has("skin") ? json.get("skin").getAsString() : "",
				json.has("device") ? json.get("device").getAsString() : "",
				json.has("packKey") ? json.get("packKey").getAsString() : "");
	}

	/**
	 * Ошибку сайта показываем по-человечески: код для нас, строка для игрока. {@code scope}
	 * уточняет код там, где одной причины мало: третий аккаунт упёрся в компьютер ({@code machine})
	 * или в интернет ({@code ip}), и игроку в этих случаях делать разное.
	 */
	private static String reason(String code, String scope) {
		return switch (code) {
			case "bad_credentials" -> "Ник или пароль не подошли.";
			case "banned" -> "Эта учётная запись заблокирована.";
			case "too_often" -> "Слишком много попыток. Подожди несколько минут.";
			case "device_gone" -> "Сохранённый вход больше не годится - введи пароль.";
			case "too_many_accounts" -> "ip".equals(scope)
					? "С этого интернета уже играют два аккаунта, третий войти не может. "
						+ "Живёте вместе - напишите администрации, аккаунт добавят в исключения."
					: "На этом компьютере уже играют два аккаунта, третий войти не может. "
						+ "Если это ошибка - напишите администрации.";
			default -> "Сайт ответил отказом. Попробуй позже.";
		};
	}

	public static Pack pack() throws IOException {
		JsonObject json = JsonParser.parseString(text(base + "/api/launcher/manifest")).getAsJsonObject();
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
			HttpURLConnection link = open(base + "/api/launcher/home", 8000);
			if (link.getResponseCode() != 200) {
				return null;
			}
			JsonObject json = JsonParser.parseString(read(link.getInputStream())).getAsJsonObject();
			List<News> news = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("news")) {
				JsonObject one = e.getAsJsonObject();
				news.add(new News(one.get("title").getAsString(), one.get("url").getAsString(),
						one.has("at") && !one.get("at").isJsonNull() ? one.get("at").getAsLong() : 0L,
						one.has("snippet") && !one.get("snippet").isJsonNull() ? one.get("snippet").getAsString() : ""));
			}
			List<String> players = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("players")) {
				players.add(e.getAsString());
			}
			JsonObject links = json.getAsJsonObject("links");
			return new Home(news, json.get("online").getAsBoolean(), players,
					links.get("register").getAsString(), links.get("wiki").getAsString(),
					links.get("map").getAsString(), links.get("forum").getAsString(), soon(json));
		} catch (Exception quiet) {
			return null;
		}
	}

	/**
	 * Афиша из ответа {@code /api/launcher/home}. Её может не быть (сайт старее лаунчера) или она
	 * может прийти кривой - тогда пустой список: окно без афиши работает, как раньше, а сломанная
	 * запись не должна отнимать новости и онлайн.
	 */
	private static List<Soon> soon(JsonObject json) {
		List<Soon> out = new ArrayList<>();
		try {
			if (!json.has("soon") || !json.get("soon").isJsonArray()) {
				return out;
			}
			for (JsonElement e : json.getAsJsonArray("soon")) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject one = e.getAsJsonObject();
				String title = string(one, "title");
				if (title.isEmpty()) {
					continue;
				}
				out.add(new Soon(string(one, "id"), string(one, "state"), title, string(one, "where"),
						string(one, "when"), number(one, "at"), number(one, "until")));
			}
		} catch (RuntimeException quiet) {
			out.clear();
		}
		return out;
	}

	private static String string(JsonObject one, String key) {
		return one.has(key) && one.get(key).isJsonPrimitive() ? one.get(key).getAsString() : "";
	}

	private static long number(JsonObject one, String key) {
		return one.has(key) && one.get(key).isJsonPrimitive() && one.get(key).getAsJsonPrimitive().isNumber()
				? one.get(key).getAsLong() : 0L;
	}

	/**
	 * Адреса Mojang и Fabric, которые сайт отдаёт через себя: {@code /mirror/<хост>/<путь>}.
	 *
	 * Зачем (29.09.2026). Сайт у игроков из России уже ходит через московского посредника, а игру
	 * лаунчер ставит напрямую - с Mojang и с Fabric, и Fabric стоит за Cloudflare, который там режут:
	 * игрок видел «Connection reset» на первой же установке, хотя через TLauncher со своими
	 * зеркалами заходил. Теперь не ответил оригинал - тот же файл берётся через наш сайт (по тому
	 * же маршруту, что и моды), а на московском маршруте - сразу через него. Суммы файлов
	 * проверяются как раньше, так что посредник ничего не может подменить незаметно.
	 */
	private static final java.util.Set<String> MIRRORED = java.util.Set.of(
		"piston-meta.mojang.com", "piston-data.mojang.com", "launchermeta.mojang.com",
		"libraries.minecraft.net", "resources.download.minecraft.net",
		"meta.fabricmc.net", "maven.fabricmc.net");

	/** Тот же адрес через наш сайт, или null, если сайт такой хост не зеркалит. */
	static String mirrored(String url) {
		URI uri = URI.create(url);
		if (!"https".equals(uri.getScheme()) || !MIRRORED.contains(uri.getHost())) {
			return null;
		}
		return base + "/mirror/" + uri.getHost() + uri.getRawPath()
			+ (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
	}

	/** Куда стучаться и в каком порядке: оригинал и зеркало, через Москву - зеркало первым. */
	private static List<String> routes(String url) {
		String mirror = mirrored(url);
		if (mirror == null) {
			return List.of(url);
		}
		return viaMoscow() ? List.of(mirror, url) : List.of(url, mirror);
	}

	public static String text(String url) throws IOException {
		IOException last = null;
		for (String route : routes(url)) {
			try {
				HttpURLConnection link = open(route, 120000);
				int code = link.getResponseCode();
				if (code != 200) {
					throw new IOException("Не ответил (" + code + "): " + route);
				}
				return read(link.getInputStream());
			} catch (IOException broken) {
				last = broken;
			}
		}
		throw last;
	}

	/** Скачать в файл, создав папки по дороге. Возвращает, сколько байт пришло. */
	/** Сколько раз пробуем вытянуть файл, прежде чем сдаться. */
	private static final int ATTEMPTS = 4;

	public static long download(String url, Path to) throws IOException {
		return download(url, to, -1L, null);
	}

	/**
	 * Скачать с докачкой и повторами.
	 *
	 * <b>Зачем.</b> Прежде файл тянулся одним {@code Files.copy}, и это было неверно в самом
	 * важном месте: <b>оборванный поток не считается ошибкой</b>. Связь пропала на середине -
	 * поток просто кончился, copy отработал без жалоб, на диске остался огрызок. Дальше не
	 * сходилась сумма, и лаунчер писал «Файл скачался испорченным», хотя файл на сервере был
	 * цел, а порвалась связь.
	 *
	 * Разбирали это 21.09.2026 на живом канале владельца: мод 11 МБ тянулся минуту, и из трёх
	 * попыток одна обрывалась. Сервер при этом отдавал файл целиком каждый раз - проверено с
	 * него самого.
	 *
	 * <b>Как теперь.</b> Хвост остаётся на диске и дотягивается запросом Range, а не качается
	 * заново: на медленном канале это разница между «дойдёт» и «не дойдёт никогда». Если
	 * сервер докачку не умеет, берём файл сначала. Сумма не сошлась - хвост негодный, стираем
	 * и начинаем чисто.
	 *
	 * @param size ожидаемый размер или -1, если неизвестен
	 * @param sha1 ожидаемая сумма или null
	 */
	public static long download(String url, Path to, long size, String sha1) throws IOException {
		List<String> routes = routes(url);
		IOException last = null;
		for (int i = 0; i < routes.size(); i++) {
			try {
				// Есть запасной путь - на первом не упираемся все четыре попытки, хватит двух.
				return downloadVia(routes.get(i), to, size, sha1, i + 1 < routes.size() ? 2 : ATTEMPTS);
			} catch (IOException broken) {
				last = broken;
			}
		}
		throw last;
	}

	private static long downloadVia(String url, Path to, long size, String sha1, int attempts) throws IOException {
		Files.createDirectories(to.getParent());
		Path temp = to.resolveSibling(to.getFileName() + ".part");
		String trouble = "связь оборвалась";
		for (int attempt = 1; attempt <= attempts; attempt++) {
			long have = Files.isRegularFile(temp) ? Files.size(temp) : 0L;
			if (size > 0 && have > size) {
				// Хвост длиннее целого файла - это мусор, а не хвост.
				have = 0L;
			}
			try {
				grab(url, temp, have);
			} catch (IOException broken) {
				// Хвост оставляем: следующая попытка продолжит с него.
				trouble = broken.getMessage() == null ? "связь оборвалась" : broken.getMessage();
				continue;
			}
			if (fits(temp, size, sha1)) {
				// Кладём на место одним движением: прерванная загрузка не оставит битый файл
				// под нужным именем.
				Files.move(temp, to, StandardCopyOption.REPLACE_EXISTING);
				return Files.size(to);
			}
			// Дотянули до конца, а сумма не та - значит, испорчен сам хвост. Чисто заново.
			Files.deleteIfExists(temp);
			trouble = "файл пришёл не целым";
		}
		Files.deleteIfExists(temp);
		throw new IOException("Не удалось скачать " + to.getFileName() + " за "
			+ attempts + " попытки: " + trouble + ". Проверь интернет и попробуй ещё раз.");
	}

	/** Тянет файл целиком или, если {@code from} больше нуля, только хвост с этого места. */
	private static void grab(String url, Path temp, long from) throws IOException {
		HttpURLConnection link = open(url, 600000);
		if (from > 0) {
			link.setRequestProperty("Range", "bytes=" + from + "-");
		}
		int code = link.getResponseCode();
		if (code != 200 && code != 206) {
			throw new IOException("Не скачалось (" + code + "): " + url);
		}
		// 206 - сервер понял Range и шлёт хвост, его дописываем. 200 - не понял и шлёт всё
		// сначала, тогда прежнее содержимое затираем.
		boolean tail = code == 206 && from > 0;
		try (InputStream in = link.getInputStream();
				java.io.OutputStream out = tail
					? Files.newOutputStream(temp, java.nio.file.StandardOpenOption.CREATE,
						java.nio.file.StandardOpenOption.APPEND)
					: Files.newOutputStream(temp, java.nio.file.StandardOpenOption.CREATE,
						java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			in.transferTo(out);
		}
	}

	/** Тот ли это файл: сходятся ли размер и сумма, насколько они известны. */
	private static boolean fits(Path file, long size, String sha1) throws IOException {
		if (!Files.isRegularFile(file)) {
			return false;
		}
		if (size > 0 && Files.size(file) != size) {
			return false;
		}
		if (sha1 == null || sha1.isEmpty()) {
			// Ни суммы, ни размера - верим тому, что пришло: так было и раньше.
			return size > 0 || Files.size(file) > 0;
		}
		return sha1.equalsIgnoreCase(Files2.sha1(file));
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
