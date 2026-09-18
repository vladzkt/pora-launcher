package ru.mcgl.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Ставит и обновляет всё, что нужно для запуска: саму игру, её библиотеки и ресурсы,
 * загрузчик Fabric и нашу сборку модов.
 *
 * Всё скачанное проверяется по сумме, поэтому повторный запуск ничего не качает заново, а
 * оборванная загрузка не оставляет за собой битого файла: он просто не сойдётся и приедет снова.
 */
public final class Installer {

	private static final String VERSIONS = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	private static final String RESOURCES = "https://resources.download.minecraft.net/";
	private static final String FABRIC = "https://meta.fabricmc.net/v2/versions/loader/";
	/** Как разрядность машины называется в правилах Mojang. */
	/** Разрядность и семейство железа в именах Mojang: x86, x86_64, arm64. См. {@link Os}. */
	private static final String ARCH = Os.arch();

	/** Куда докладываем, что происходит: строка для человека и доля от нуля до единицы. */
	public interface Progress {
		void say(String what, double done);
	}

	/** Всё, что нужно, чтобы собрать команду запуска. */
	/** {@code java} - та Java, которой запускать игру: скачанная Mojang или null, если своя сгодится. */
	public record Plan(List<Path> classpath, Path natives, String mainClass, Path assets, String assetIndex,
			Path java) {
	}

	private final Path root;

	public Installer(Path root) {
		this.root = root;
	}

	/**
	 * Что лаунчер взял бы на этой системе: имена библиотек, которые проходят правила.
	 *
	 * Нужно затем, что Мака и Линукса у нас под рукой нет, а проверить выбор надо. Вместе с
	 * {@code -Dporakopatb.os} и {@code -Dporakopatb.arch} это даёт полный ответ, ничего не
	 * скачивая: видно, что на Маке едут natives-macos, а на Apple Silicon - natives-macos-arm64.
	 */
	public List<String> preview(Site.Pack pack) throws IOException {
		JsonObject version = versionJson(pack.minecraft());
		List<String> taken = new ArrayList<>();
		for (JsonElement element : version.getAsJsonArray("libraries")) {
			JsonObject lib = element.getAsJsonObject();
			if (allowed(lib)) {
				taken.add(lib.get("name").getAsString());
			}
		}
		return taken;
	}

	public Plan install(Site.Pack pack, Progress say) throws IOException {
		return install(pack, "", say);
	}

	public Plan install(Site.Pack pack, String packKey, Progress say) throws IOException {
		say.say("Смотрю, что нового", 0.02);
		JsonObject version = versionJson(pack.minecraft());

		List<Path> classpath = new ArrayList<>();
		Path natives = root.resolve("natives").resolve(pack.minecraft());

		say.say("Игра", 0.06);
		Path client = root.resolve("versions").resolve(pack.minecraft()).resolve(pack.minecraft() + ".jar");
		JsonObject clientInfo = version.getAsJsonObject("downloads").getAsJsonObject("client");
		need(client, clientInfo.get("size").getAsLong(), clientInfo.get("sha1").getAsString(),
				clientInfo.get("url").getAsString());

		say.say("Библиотеки игры", 0.12);
		classpath.addAll(libraries(version.getAsJsonArray("libraries"), natives));

		say.say("Загрузчик Fabric", 0.30);
		JsonObject fabric = fabricProfile(pack.minecraft(), pack.fabric());
		classpath.addAll(fabricLibraries(fabric.getAsJsonArray("libraries")));
		String mainClass = fabric.get("mainClass").getAsString();

		// Клиентский джарник в конце пути: Fabric ждёт его последним.
		classpath.add(client);

		say.say("Ресурсы игры", 0.45);
		JsonObject index = version.getAsJsonObject("assetIndex");
		String assetIndexId = index.get("id").getAsString();
		Path assets = root.resolve("assets");
		assets(index, assets, say);

		say.say("Java для игры", 0.88);
		Path java = runtime(version, say);

		say.say("Моды сервера", 0.92);
		mods(pack, packKey);

		options();

		say.say("Готово", 1.0);
		return new Plan(classpath, natives, mainClass, assets, assetIndexId, java);
	}

	// --- игра -------------------------------------------------------------------------------

	private JsonObject versionJson(String id) throws IOException {
		Path local = root.resolve("versions").resolve(id).resolve(id + ".json");
		if (!Files.isRegularFile(local)) {
			JsonObject all = JsonParser.parseString(Site.text(VERSIONS)).getAsJsonObject();
			String url = null;
			for (JsonElement e : all.getAsJsonArray("versions")) {
				JsonObject v = e.getAsJsonObject();
				if (id.equals(v.get("id").getAsString())) {
					url = v.get("url").getAsString();
					break;
				}
			}
			if (url == null) {
				throw new IOException("Mojang не знает версию " + id);
			}
			Files.createDirectories(local.getParent());
			Files.writeString(local, Site.text(url), StandardCharsets.UTF_8);
		}
		return JsonParser.parseString(Files.readString(local, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/** Библиотеки под нашу систему; те, что с машинным кодом, ещё и распаковываются. */
	private List<Path> libraries(JsonArray list, Path natives) throws IOException {
		List<Path> out = new ArrayList<>();
		for (JsonElement e : list) {
			JsonObject lib = e.getAsJsonObject();
			if (!allowed(lib)) {
				continue;
			}
			JsonObject downloads = lib.getAsJsonObject("downloads");
			if (downloads == null || !downloads.has("artifact")) {
				continue;
			}
			JsonObject art = downloads.getAsJsonObject("artifact");
			Path file = root.resolve("libraries").resolve(art.get("path").getAsString());
			need(file, art.get("size").getAsLong(), art.get("sha1").getAsString(), art.get("url").getAsString());
			if (lib.get("name").getAsString().contains("natives-")) {
				Files2.unpackNatives(file, natives);
			} else {
				out.add(file);
			}
		}
		return out;
	}

	/**
	 * Подходит ли машинный код этой библиотеки нашему железу.
	 *
	 * Правила в манифесте различают только систему: у lwjgl-glfw:natives-macos и
	 * natives-macos-arm64 правило одно и то же - «osx». Архитектура зашита в имя, и выбирать по
	 * ней приходится самим. Пока этого не было, на Windows качались три набора нативов разом
	 * (обычный, arm64 и x86), а на Маке в одну папку легли бы и Intel, и Apple Silicon - чей
	 * файл распакуется первым, тот и остался бы.
	 *
	 * Заплатка macos-patch (freetype) - не про архитектуру, она нужна любому Маку.
	 */
	private static boolean archMatches(String name) {
		int at = name.indexOf(":natives-");
		if (at < 0) {
			return true;
		}
		String classifier = name.substring(at + ":natives-".length());
		if (classifier.endsWith("-patch")) {
			return true;
		}
		String family = switch (Os.name()) {
			case Os.WINDOWS -> "windows";
			case Os.MAC -> "macos";
			default -> "linux";
		};
		String want = "x86_64".equals(ARCH) ? family : family + "-" + ARCH;
		return classifier.equals(want);
	}

	/** Правила библиотеки: разрешена ли она на этой системе. */
	private static boolean allowed(JsonObject lib) {
		if (!archMatches(lib.get("name").getAsString())) {
			return false;
		}
		if (!lib.has("rules")) {
			return true;
		}
		boolean allow = false;
		for (JsonElement e : lib.getAsJsonArray("rules")) {
			JsonObject rule = e.getAsJsonObject();
			boolean applies = true;
			if (rule.has("os")) {
				JsonObject os = rule.getAsJsonObject("os");
				if (os.has("name")) {
					// Раньше здесь стояла строка "windows", и на Маке с Линуксом правила читались
					// наоборот: windows-библиотеки приезжали, родные отбрасывались.
					applies = Os.name().equals(os.get("name").getAsString());
				}
				// Разрядность тоже важна: без неё к нам приезжали сборки под x86 и arm64.
				if (applies && os.has("arch")) {
					applies = ARCH.equals(os.get("arch").getAsString());
				}
			}
			if (applies) {
				allow = "allow".equals(rule.get("action").getAsString());
			}
		}
		return allow;
	}

	// --- Fabric -----------------------------------------------------------------------------

	private JsonObject fabricProfile(String mc, String loader) throws IOException {
		Path local = root.resolve("versions").resolve("fabric-" + mc + "-" + loader + ".json");
		if (!Files.isRegularFile(local)) {
			Files.createDirectories(local.getParent());
			Files.writeString(local, Site.text(FABRIC + mc + "/" + loader + "/profile/json"), StandardCharsets.UTF_8);
		}
		return JsonParser.parseString(Files.readString(local, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	/** У Fabric библиотеки описаны координатами, а не ссылкой: путь собираем сами. */
	private List<Path> fabricLibraries(JsonArray list) throws IOException {
		List<Path> out = new ArrayList<>();
		for (JsonElement e : list) {
			JsonObject lib = e.getAsJsonObject();
			String name = lib.get("name").getAsString();
			String base = lib.has("url") ? lib.get("url").getAsString() : "https://maven.fabricmc.net/";
			String rel = mavenPath(name);
			Path file = root.resolve("libraries").resolve(rel);
			if (!Files.isRegularFile(file)) {
				Site.download(base + rel, file);
			}
			out.add(file);
		}
		return out;
	}

	/** «net.fabricmc:tiny-mappings-parser:0.3.0» превращается в путь внутри хранилища. */
	static String mavenPath(String coords) {
		String[] parts = coords.split(":");
		String group = parts[0].replace('.', '/');
		String artifact = parts[1];
		String version = parts[2];
		String tail = parts.length > 3 ? "-" + parts[3] : "";
		return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + tail + ".jar";
	}

	// --- ресурсы ----------------------------------------------------------------------------

	private void assets(JsonObject index, Path assets, Progress say) throws IOException {
		String id = index.get("id").getAsString();
		Path indexFile = assets.resolve("indexes").resolve(id + ".json");
		need(indexFile, index.get("size").getAsLong(), index.get("sha1").getAsString(), index.get("url").getAsString());

		JsonObject objects = JsonParser.parseString(Files.readString(indexFile, StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonObject("objects");
		Map<String, String> missing = new LinkedHashMap<>();
		for (String name : objects.keySet()) {
			JsonObject o = objects.getAsJsonObject(name);
			String hash = o.get("hash").getAsString();
			Path file = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
			if (!Files2.matches(file, o.get("size").getAsLong(), null)) {
				missing.put(hash, hash.substring(0, 2) + "/" + hash);
			}
		}
		if (missing.isEmpty()) {
			return;
		}

		// Ресурсов тысячи и каждый крошечный: по одному это полчаса, в шестнадцать потоков - минуты.
		ExecutorService pool = Executors.newFixedThreadPool(16);
		AtomicInteger done = new AtomicInteger();
		int total = missing.size();
		List<Future<?>> tasks = new ArrayList<>();
		for (Map.Entry<String, String> entry : missing.entrySet()) {
			Path file = assets.resolve("objects").resolve(entry.getKey().substring(0, 2)).resolve(entry.getKey());
			tasks.add(pool.submit(() -> {
				try {
					Site.download(RESOURCES + entry.getValue(), file);
				} catch (Exception broken) {
					throw new RuntimeException(broken);
				}
				int n = done.incrementAndGet();
				if (n % 50 == 0 || n == total) {
					say.say("Ресурсы игры: " + n + " из " + total, 0.45 + 0.45 * n / total);
				}
			}));
		}
		pool.shutdown();
		try {
			for (Future<?> task : tasks) {
				task.get();
			}
		} catch (java.util.concurrent.ExecutionException failed) {
			throw new IOException("Не скачались ресурсы игры: " + failed.getCause().getMessage());
		} catch (InterruptedException stopped) {
			// Окно закрыли посреди загрузки: восстанавливаем флаг и выходим по-человечески.
			Thread.currentThread().interrupt();
			pool.shutdownNow();
			throw new IOException("Загрузка прервана");
		}
	}


	// --- первые настройки игры --------------------------------------------------------------

	/**
	 * Настройки для того, кто запускается впервые.
	 *
	 * Ванильные значения по умолчанию рассчитаны на старые экраны: масштаб интерфейса «авто» на
	 * нынешнем мониторе даёт четырёхкратный, и надписи занимают полэкрана, а вся громкость стоит
	 * на единице - игра встречает человека рёвом. Первое впечатление портится ещё до того, как он
	 * что-то увидел, а искать это в настройках новичок не пойдёт.
	 *
	 * Пишем только если файла нет вовсе. Свои настройки игрока не трогаем никогда: он их менял
	 * руками, и переписать их значило бы отобрать.
	 */
	private void options() throws IOException {
		Path file = root.resolve("options.txt");
		if (Files.exists(file)) {
			return;
		}
		String text = String.join("\n",
				// Номер версии нужен самой игре: без него она считает файл древним и прогоняет
				// через починку старых настроек. 3955 - это 1.21.1.
				"version:3955",
				"guiScale:2",
				"soundCategory_master:0.35",
				"soundCategory_music:0.2",
				"lang:ru_ru") + "\n";
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	// --- наши моды --------------------------------------------------------------------------

	/** Джарник мода, который в режиме разработчика лаунчер не трогает. */
	private static final String OURS = "galaxy-mod-";

	/**
	 * Папка модов приводится ровно к списку с сайта: лишние джарники удаляются.
	 *
	 * Кроме одного случая. Если в папке игры лежит файл {@code dev.flag}, лаунчер не трогает
	 * джарник самого мода: не сверяет его с сайтом и не удаляет. Иначе проверить свежую сборку
	 * невозможно в принципе - положил её в mods, нажал «Играть», и лаунчер тут же вернул на место
	 * ту, что лежит в паке. А чтобы попасть в пак, сборка должна сперва уехать на сайт, то есть
	 * стать общей для всех игроков; выходит, проверять не на чем.
	 *
	 * Флажок ставится руками и только себе - у игроков его нет, и для них ничего не меняется.
	 */
	private void mods(Site.Pack pack, String packKey) throws IOException {
		boolean dev = Files.exists(root.resolve("dev.flag"));
		Path index = Vault.store().resolve("index");
		java.util.Properties known = new java.util.Properties();
		if (Files.isRegularFile(index)) {
			try (InputStream in = Files.newInputStream(index)) {
				known.load(in);
			}
		}

		for (Site.PackFile f : pack.files()) {
			String name = f.path().substring(f.path().lastIndexOf('/') + 1);
			if (dev && name.startsWith(OURS)) {
				continue;
			}
			// Сумма записана в описи: сверять её так дешевле, чем каждый раз расшифровывать
			// контейнер целиком ради одной проверки.
			if (f.sha1().equalsIgnoreCase(known.getProperty(f.path(), ""))
					&& Files.isRegularFile(Vault.container(f.path()))) {
				continue;
			}
			Path plain = Files.createTempFile("pk-dl-", ".tmp");
			Site.download(f.url(), plain);
			if (f.sha1() != null && !f.sha1().isEmpty() && !f.sha1().equalsIgnoreCase(Files2.sha1(plain))) {
				Files.deleteIfExists(plain);
				throw new IOException("Файл скачался испорченным: " + name);
			}
			Vault.put(plain, f.path(), packKey);
			known.setProperty(f.path(), f.sha1());
		}

		// Лишние контейнеры от прошлых сборок убираем: иначе кладовая растёт вечно.
		Set<String> wanted = new HashSet<>();
		for (Site.PackFile f : pack.files()) {
			wanted.add(Vault.container(f.path()).getFileName().toString());
		}
		for (Path there : Files2.listFiles(Vault.store())) {
			String name = there.getFileName().toString();
			if (name.endsWith(".dat") && !wanted.contains(name)) {
				Files.deleteIfExists(there);
			}
		}
		for (Object key : new java.util.ArrayList<>(known.keySet())) {
			boolean still = pack.files().stream().anyMatch(f -> f.path().equals(key));
			if (!still) {
				known.remove(key);
			}
		}

		Files.createDirectories(Vault.store());
		try (OutputStream out = Files.newOutputStream(index)) {
			known.store(out, "опись сборки: путь = сумма");
		}

		// В папке игры модов быть не должно вовсе - в этом и смысл. Старые джарники от прежних
		// версий лаунчера сносим, папку тоже.
		Path mods = root.resolve("mods");
		if (Files.isDirectory(mods)) {
			for (Path there : Files2.listFiles(mods)) {
				String name = there.getFileName().toString();
				if (dev && name.startsWith(OURS)) {
					continue;
				}
				Files.deleteIfExists(there);
			}
			if (Files2.listFiles(mods).isEmpty()) {
				Files.deleteIfExists(mods);
			}
		}
	}

	/** Запасной путь: моды открытыми в папке игры, как было до кладовой. */
	private void plainMods(Site.Pack pack, boolean dev) throws IOException {
		Path mods = root.resolve("mods");
		Files.createDirectories(mods);
		Set<String> wanted = new HashSet<>();
		for (Site.PackFile f : pack.files()) {
			Path file = root.resolve(f.path());
			String name = file.getFileName().toString();
			wanted.add(name);
			if (dev && name.startsWith(OURS)) {
				continue;
			}
			need(file, f.size(), f.sha1(), f.url());
		}
		for (Path there : Files2.listFiles(mods)) {
			String name = there.getFileName().toString();
			if (dev && name.startsWith(OURS)) {
				continue;
			}
			if (!wanted.contains(name)) {
				Files.deleteIfExists(there);
			}
		}
	}

	/** Где Mojang держит список своих сред Java. */
	private static final String RUNTIMES =
			"https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

	/**
	 * Java для игры: ровно та, что Mojang считает правильной для этой версии.
	 *
	 * Зачем своя, когда лаунчер и так работает на какой-то. На Windows Java едет внутри
	 * установщика, и вопроса нет. На Маке и Линуксе игрок ставит её сам - и ставит какую попало:
	 * игре 1.21.1 нужна двадцать первая, а в системе часто семнадцатая или вовсе восьмая. Пусть
	 * лаунчер запускается на любой свежей, а игру запускает той, что скачал сам.
	 *
	 * Список у Mojang разложен по площадкам (mac-os-arm64, linux, windows-x64) и по составам
	 * (java-runtime-delta - это 21). В манифесте состава - дерево файлов: обычные качаем по сумме,
	 * ссылки повторяем ссылками, а где сказано executable - ставим право на запуск, иначе на Маке
	 * и Линуксе такой java не запустится вовсе.
	 *
	 * Своя среда не скачивается, если лаунчер и так работает на подходящей: на Windows это
	 * вложенная Java, и второй такой же на диске никому не нужно.
	 */
	private Path runtime(JsonObject version, Progress say) throws IOException {
		if (!version.has("javaVersion")) {
			return null;
		}
		JsonObject wanted = version.getAsJsonObject("javaVersion");
		String component = wanted.get("component").getAsString();
		int major = wanted.get("majorVersion").getAsInt();
		// Своя Java подходит - ничего не качаем: на Windows она внутри установщика, а на Маке и
		// Линуксе игрок мог поставить ту же двадцать первую сам.
		if (Runtime.version().feature() >= major) {
			return null;
		}
		String platform = platform();
		JsonObject all = JsonParser.parseString(Site.text(RUNTIMES)).getAsJsonObject();
		if (!all.has(platform)) {
			return null;
		}
		JsonObject parts = all.getAsJsonObject(platform);
		if (!parts.has(component) || parts.getAsJsonArray(component).isEmpty()) {
			return null;
		}
		JsonObject manifest = parts.getAsJsonArray(component).get(0).getAsJsonObject()
				.getAsJsonObject("manifest");
		Path into = root.resolve("runtime").resolve(component).resolve(platform);
		JsonObject files = JsonParser.parseString(Site.text(manifest.get("url").getAsString()))
				.getAsJsonObject().getAsJsonObject("files");
		int done = 0;
		int total = files.size();
		for (String path : files.keySet()) {
			JsonObject entry = files.getAsJsonObject(path);
			Path target = into.resolve(path);
			String type = entry.get("type").getAsString();
			if ("directory".equals(type)) {
				Files.createDirectories(target);
			} else if ("link".equals(type)) {
				link(target, entry.get("target").getAsString());
			} else if (entry.has("downloads")) {
				JsonObject raw = entry.getAsJsonObject("downloads").getAsJsonObject("raw");
				Files.createDirectories(target.getParent());
				need(target, raw.get("size").getAsLong(), raw.get("sha1").getAsString(),
						raw.get("url").getAsString());
				if (entry.has("executable") && entry.get("executable").getAsBoolean()) {
					executable(target);
				}
			}
			if (++done % 50 == 0) {
				say.say("Java для игры", 0.88 + 0.03 * done / Math.max(1, total));
			}
		}
		Path binary = into.resolve("bin").resolve(Os.windows() ? "javaw.exe" : "java");
		Path bundled = into.resolve("jre.bundle/Contents/Home/bin/java");
		if (!Files.isRegularFile(binary) && Files.isRegularFile(bundled)) {
			binary = bundled;
		}
		if (!Files.isRegularFile(binary)) {
			return null;
		}
		executable(binary);
		return binary;
	}

	/** Имя площадки в списке Mojang. */
	private static String platform() {
		if (Os.windows()) {
			return "arm64".equals(ARCH) ? "windows-arm64" : "x86".equals(ARCH) ? "windows-x86" : "windows-x64";
		}
		if (Os.mac()) {
			return "arm64".equals(ARCH) ? "mac-os-arm64" : "mac-os";
		}
		return "x86".equals(ARCH) ? "linux-i386" : "linux";
	}

	/** Ссылка внутри среды: где нельзя - копия, лишь бы файл оказался на месте. */
	private static void link(Path target, String to) throws IOException {
		Files.createDirectories(target.getParent());
		if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.createSymbolicLink(target, Path.of(to));
		} catch (IOException | UnsupportedOperationException noLinks) {
			Path source = target.getParent().resolve(to).normalize();
			if (Files.isRegularFile(source)) {
				Files.copy(source, target);
			}
		}
	}

	/** Право на запуск: без него скачанная java на Маке и Линуксе не стартует. */
	private static void executable(Path file) {
		try {
			java.util.Set<java.nio.file.attribute.PosixFilePermission> rights =
					new HashSet<>(Files.getPosixFilePermissions(file));
			rights.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
			rights.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
			rights.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
			Files.setPosixFilePermissions(file, rights);
		} catch (IOException | UnsupportedOperationException windows) {
			// На Windows прав на запуск нет вовсе - и не надо.
		}
	}

	private void need(Path file, long size, String sha1, String url) throws IOException {
		if (Files2.matches(file, size, sha1)) {
			return;
		}
		Site.download(url, file);
		if (sha1 != null && !sha1.isEmpty() && !sha1.equalsIgnoreCase(Files2.sha1(file))) {
			Files.deleteIfExists(file);
			throw new IOException("Файл скачался испорченным: " + file.getFileName());
		}
	}
}
