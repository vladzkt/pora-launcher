package ru.mcgl.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Лаунчер обновляет сам себя.
 *
 * Установленную программу мы не трогаем: новый джарник ложится в {@code %APPDATA%\.porakopatb}
 * и запускается вместо неё. Переустанавливать ничего не нужно, а если обновление не заладится -
 * останется рабочая установленная версия.
 *
 * Проверка идёт до появления окна и с короткими сроками ожидания: не ответил сайт за пару
 * секунд - запускаемся с тем, что есть. Лаунчер, который не открывается из-за лежащего сайта,
 * хуже необновлённого.
 */
public final class Update {

	/** Мы уже перезапущенный экземпляр: второй раз обновляться незачем. */
	static final String CHILD = "--child";

	private static final int CONNECT_MS = 4000;
	private static final int READ_MS = 6000;

	private Update() {
	}

	/** Версия из манифеста джарника; в среде разработки её нет - тогда нули. */
	static String running() {
		String known = Launcher.class.getPackage().getImplementationVersion();
		return known == null || known.isBlank() ? "0.0.0" : known;
	}

	private static Path store() {
		return Files2.home().resolve("launcher");
	}

	/**
	 * Если рядом или на сайте есть версия новее - перезапускаемся с ней и больше не возвращаемся.
	 * Любая осечка означает «работаем как есть».
	 */
	static void apply(String[] args) {
		for (String arg : args) {
			if (CHILD.equals(arg)) {
				return;
			}
		}
		try {
			// Сайт спрашиваем ВСЕГДА, а не только когда рядом ничего нет.
			//
			// Раньше было иначе: нашёлся джарник поновее рядом - на сайт уже не ходим. И это
			// обновление ломало: старший лаунчер видит рядом свежую версию, перезапускается в неё
			// ребёнком, а ребёнок обновление не проверяет вовсе. Выходило, что после первого же
			// обновления лаунчер навсегда застревал на нём и следующих не получал никогда.
			Path nearby = newestNearby();
			String have = nearby == null ? running() : versionOf(nearby.getFileName().toString());
			Path fetched = fetchIfNewer(have == null ? running() : have);
			Path best = fetched == null ? nearby : fetched;
			if (best != null) {
				restart(best, args);
			}
		} catch (Throwable quiet) {
			// Обновление - удобство, а не условие работы: молча запускаемся дальше.
		}
	}

	/**
	 * Открытый ключ владельца: им сверяется подпись скачанного джарника.
	 *
	 * Закрытая часть лежит только на машине владельца и в репозиторий не коммитится, так что
	 * подписать сборку не может ни сервер, ни тот, кто до сервера доберётся - в этом весь смысл
	 * (предложение брата владельца, 19.09.2026: «ключ держим офлайн, открытую часть зашиваем в
	 * лаунчер»). Сменить ключ можно только новой сборкой лаунчера.
	 */
	private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEAtqxYPHEdWsPvmscsPA0UZ1zmCutJV3wHQnXVwLNh3vU=";

	/**
	 * Верна ли подпись джарника.
	 *
	 * Подпись лежит рядом файлом {@code <имя>.sig} - тем же, что кладёт tools/Sign.java. Нет файла,
	 * кривая подпись, чужой ключ - ответ один: нет. Сумма защищает от порчи по дороге, подпись -
	 * от подмены, и одно другое не заменяет.
	 */
	static boolean signed(Path jar) {
		try {
			Path sig = jar.resolveSibling(jar.getFileName() + ".sig");
			if (!Files.isRegularFile(sig)) {
				return false;
			}
			java.security.PublicKey key = java.security.KeyFactory.getInstance("Ed25519")
					.generatePublic(new java.security.spec.X509EncodedKeySpec(
						java.util.Base64.getDecoder().decode(PUBLIC_KEY)));
			java.security.Signature check = java.security.Signature.getInstance("Ed25519");
			check.initVerify(key);
			check.update(Files.readAllBytes(jar));
			return check.verify(java.util.Base64.getDecoder().decode(
					Files.readString(sig, StandardCharsets.UTF_8).trim()));
		} catch (Exception broken) {
			return false;
		}
	}

	/**
	 * Уже скачанный джарник новее нашего: запускаемся с него, сайт не спрашиваем.
	 *
	 * <b>Только со знакомой суммой.</b> Раньше здесь проверялось одно имя файла: положи кто-нибудь
	 * в эту папку «pora-launcher-9.9.9-all.jar» - и лаунчер исполнял бы его при каждом запуске, под
	 * своим именем и со своими правами. Записать туда файл может лишь тот, кто уже хозяйничает на
	 * машине игрока, - и всё же подставляться незачем: разбор в чате 19.09.2026 указал на это
	 * справедливо.
	 *
	 * Теперь рядом лежит {@code known.sha1} - сумма того джарника, который мы сами скачали и
	 * сверили. Не совпало или записи нет - файл не наш, запускаемся своей версией.
	 */
	private static Path newestNearby() throws IOException {
		Path dir = store();
		if (!Files.isDirectory(dir)) {
			return null;
		}
		Path record = dir.resolve("known.sha1");
		if (!Files.isRegularFile(record)) {
			return null;
		}
		String known = Files.readString(record, StandardCharsets.UTF_8).trim().toLowerCase(java.util.Locale.ROOT);
		if (known.isEmpty()) {
			return null;
		}
		Path best = null;
		String bestVersion = running();
		for (Path file : list(dir)) {
			String version = versionOf(file.getFileName().toString());
			if (version == null || !newer(version, bestVersion)) {
				continue;
			}
			if (!known.equalsIgnoreCase(Files2.sha1(file)) || !signed(file)) {
				continue;
			}
			best = file;
			bestVersion = version;
		}
		return best;
	}

	private static List<Path> list(Path dir) throws IOException {
		List<Path> out = new ArrayList<>();
		try (var stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile).forEach(out::add);
		}
		return out;
	}

	/** «pora-launcher-1.0.3-all.jar» -> «1.0.3»; чужие файлы пропускаем. */
	static String versionOf(String name) {
		if (!name.startsWith("pora-launcher-") || !name.endsWith("-all.jar")) {
			return null;
		}
		String middle = name.substring("pora-launcher-".length(), name.length() - "-all.jar".length());
		return middle.matches("\\d+\\.\\d+\\.\\d+") ? middle : null;
	}

	/** Больше ли первая версия второй. Числа сравниваем как числа: 1.0.10 новее 1.0.9. */
	static boolean newer(String a, String b) {
		String[] left = a.split("\\.");
		String[] right = b.split("\\.");
		for (int i = 0; i < 3; i++) {
			int one = i < left.length ? number(left[i]) : 0;
			int two = i < right.length ? number(right[i]) : 0;
			if (one != two) {
				return one > two;
			}
		}
		return false;
	}

	private static int number(String what) {
		try {
			return Integer.parseInt(what);
		} catch (NumberFormatException broken) {
			return 0;
		}
	}

	private static Path fetchIfNewer(String have) throws IOException {
		HttpURLConnection link = (HttpURLConnection) URI.create(Site.BASE + "/api/launcher/version")
				.toURL().openConnection();
		link.setConnectTimeout(CONNECT_MS);
		link.setReadTimeout(READ_MS);
		link.setRequestProperty("User-Agent", "PoraKopatb-Launcher/" + running());
		if (link.getResponseCode() != 200) {
			return null;
		}
		JsonObject json;
		try (InputStream in = link.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			json = JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
		}
		if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
			return null;
		}
		String version = json.get("version").getAsString();
		if (!newer(version, have)) {
			return null;
		}

		// Без суммы не скачиваем вовсе: джарник, который мы потом запустим, обязан быть сверен.
		// Раньше отсутствующая сумма означала «ну и ладно» - и это была единственная проверка на
		// пути от сайта до запуска чужого кода.
		String sha1 = json.has("sha1") ? json.get("sha1").getAsString() : "";
		if (sha1.isEmpty()) {
			return null;
		}
		Path target = store().resolve("pora-launcher-" + version + "-all.jar");
		Site.download(Site.BASE + json.get("url").getAsString(), target);
		if (!sha1.equalsIgnoreCase(Files2.sha1(target))) {
			Files.deleteIfExists(target);
			return null;
		}
		// Подпись владельца - последнее слово. Её кладут рядом с джарником тем же именем плюс .sig;
		// нет её или не сходится - обновление не ставим и остаёмся на своей версии.
		Path sig = target.resolveSibling(target.getFileName() + ".sig");
		try {
			Site.download(Site.BASE + json.get("url").getAsString() + ".sig", sig);
		} catch (IOException noSignature) {
			Files.deleteIfExists(target);
			return null;
		}
		if (!signed(target)) {
			Files.deleteIfExists(target);
			Files.deleteIfExists(sig);
			return null;
		}
		// Запоминаем сумму: с неё и только с неё этот джарник потом запустится.
		Files.writeString(store().resolve("known.sha1"), sha1.toLowerCase(java.util.Locale.ROOT),
				StandardCharsets.UTF_8);
		// Прошлые версии не копим: нужна только та, с которой запускаемся.
		for (Path old : list(store())) {
			if (!old.equals(target) && versionOf(old.getFileName().toString()) != null) {
				Files.deleteIfExists(old);
			}
		}
		return target;
	}

	/**
	 * Запускаем новую версию тем же Java, что внутри установленной программы, и уходим.
	 * Свои доводы передаём дальше: иначе после обновления {@code --install} открыл бы окно.
	 */
	private static void restart(Path jar, String[] args) throws IOException {
		boolean window = args.length == 0;
		Path java = Path.of(System.getProperty("java.home"), "bin",
				System.getProperty("os.name", "").toLowerCase().contains("win")
						? (window ? "javaw.exe" : "java.exe")
						: "java");
		List<String> line = new ArrayList<>();
		line.add(java.toString());
		line.add("-jar");
		line.add(jar.toString());
		line.addAll(List.of(args));
		line.add(CHILD);
		ProcessBuilder start = new ProcessBuilder(line).directory(jar.getParent().toFile());
		if (!window) {
			start.inheritIO();
		}
		Process child = start.start();
		if (!window) {
			try {
				System.exit(child.waitFor());
			} catch (InterruptedException stopped) {
				Thread.currentThread().interrupt();
			}
		}
		System.exit(0);
	}
}
