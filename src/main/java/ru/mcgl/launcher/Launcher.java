package ru.mcgl.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * Лаунчер «Пора Копать».
 *
 * Одно окно: ник, пароль, кнопка. Всё остальное - скачивание игры, Fabric и модов - он делает
 * сам и показывает полосой.
 *
 * Пароль на диск не ложится никогда. С галочкой «запомнить» сайт выдаёт отдельный долгий ключ
 * для этой машины - он и хранится рядом с ником; годится только для входа в лаунчер и пропадает,
 * когда игрок меняет пароль.
 *
 * Для проверки без окна: {@code --check} печатает список сборки, {@code --install} доводит
 * установку до конца и ничего не запускает, {@code --shot файл.png} рисует окно в файл.
 */
public final class Launcher {

	private static final int WIDTH = 480;
	private static final int SIDE = 372;
	// 560, а не 520 (01.10.2026): правой колонке понадобился блок «Скоро» - ближайшие события сервера.
	private static final int HEIGHT = 560;
	/** Сколько событий афиши показывать: строка на каждое. */
	private static final int SOON_LINES = 3;
	// Ровно доля картинки 920x320 при ширине окна: так она видна целиком и без искажений.
	private static final int HEADER = 167;
	private static final int PAD = 30;

	private final Properties settings = new Properties();
	/** Куда ставится игра. По умолчанию рядом с настройками, но игрок может увести на другой диск. */
	private Path root = Files2.home();

	private JFrame frame;
	private JTextField nick;
	private JPasswordField password;
	private Skin.GoldButton play;
	private Skin.Check remember;
	private JPanel passBlock;
	private JPanel newsBox;
	/** Блок «Скоро»: заголовок и строки событий; спрятан, пока сайт не прислал афишу. */
	private JPanel soonBlock;
	private JPanel soonBox;
	/** Афиша из последнего ответа сайта: по ней раз в полминуты пересчитывается «через сколько». */
	private java.util.List<Site.Soon> soon = java.util.List.of();
	private Timer soonTimer;
	private JLabel onlineLine;
	/** Строка «Сейчас в игре»: лица и ники, когда игроков немного. */
	private JPanel onlineBox;
	private JLabel gameLine;
	private JLabel packLine;
	private Skin.Bar bar;
	private JLabel status;
	private JPanel crashBox;
	private Skin.Link crashLink;
	/** Строка «Привязать Телеграм» под ходом дел: видна, пока сайт говорит need_tg. */
	private JPanel tgBox;
	/** Куда ведёт кнопка «Привязать Телеграм»: ссылка в бота с кодом из последнего ответа сайта. */
	private volatile String tgLinkNow = "";
	/** Когда пришла tgLinkNow. Код в ней сайт держит живым не меньше пяти минут с ответа, дальше - как повезёт. */
	private volatile long tgLinkAt;
	/** Свежую ссылку уже спрашиваем у сайта: второе нажатие кнопки не шлёт второй вход. */
	private volatile boolean tgAsking;
	private Timer spinner;
	private boolean working;

	public static void main(String[] args) throws Exception {
		// Системный прокси Windows - до первого же запроса (29.09.2026). Java его по умолчанию не
		// видит и идёт напрямую, а у части игроков интернет на компьютере работает только через
		// прокси: браузер открывал сайт, лаунчер получал «Connection reset» и не мог даже
		// обновиться (Kirito). Прокси не отвечает - идём напрямую, как раньше: см. Net.
		Net.systemProxiesWithFallback();
		// Сначала - куда ходить: основной адрес или московское зеркало для игроков из РФ.
		Site.chooseRoute();
		// До окна: если на сайте лежит версия новее, запустимся уже с ней.
		Update.apply(args);
		if (args.length > 0 && "--plan".equals(args[0])) {
			plan();
			return;
		}
		if (args.length > 0 && ("--check".equals(args[0]) || "--install".equals(args[0]))) {
			console(args);
			return;
		}
		if (args.length > 0 && "--shot".equals(args[0])) {
			// Третьим доводом «setup» снимается окно настроек, «tg» - главное с отказом «сначала
			// привяжи Телеграм» (самая тесная раскладка: поле пароля, ошибка и кнопка разом), иначе главное.
			shot(args.length > 1 ? args[1] : "launcher.png", args.length > 2 ? args[2] : "");
			return;
		}
		// Отпечаток железа считается в фоне, пока человек смотрит на окно: к «Играть» он уже готов,
		// и вход не ждёт ни реестра, ни PowerShell. После Update.apply - чтобы не считать его в том
		// экземпляре, который сейчас уступит место новой версии.
		Hardware.warm();
		SwingUtilities.invokeLater(() -> new Launcher().show());
	}

	/**
	 * Что поедет на этой системе: список библиотек по правилам, без единой загрузки.
	 *
	 * Система и железо берутся из {@link Os}, а их можно перебить свойствами - так проверяется
	 * Мак и Линукс с чужой машины: {@code java -Dporakopatb.os=osx -Dporakopatb.arch=arm64 -jar ...
	 * --plan}.
	 */
	private static void plan() throws Exception {
		Site.Pack pack = Site.pack();
		System.out.println("Система: " + Os.name() + " " + Os.arch());
		java.util.List<String> libs = new Installer(Files2.home()).preview(pack);
		System.out.println("Библиотек по правилам: " + libs.size());
		for (String lib : libs) {
			if (lib.contains("natives")) {
				System.out.println("  натив: " + lib);
			}
		}
	}

	/** Проверка из командной строки: окно не открывается, игра не запускается. */
	private static void console(String[] args) throws Exception {
		Site.Pack pack = Site.pack();
		System.out.println("Сборка " + pack.version() + ": Minecraft " + pack.minecraft()
				+ ", Fabric " + pack.fabric());
		for (Site.PackFile f : pack.files()) {
			System.out.printf("  %-42s %7d КБ%n", f.path(), f.size() / 1024);
		}
		if ("--check".equals(args[0])) {
			return;
		}
		Path root = Files2.home();
		System.out.println("Ставлю в " + root);
		Installer.Plan plan = new Installer(root).install(pack, (what, done) ->
				System.out.printf("  [%3.0f%%] %s%n", done * 100, what));
		System.out.println("Точка входа: " + plan.mainClass());
		System.out.println("В пути классов: " + plan.classpath().size() + " файлов");
	}

	/** Рисуем окно в файл, чтобы посмотреть на него, никому его не показывая. */
	private static void shot(String file, String mode) throws Exception {
		boolean setup = "setup".equals(mode);
		SwingUtilities.invokeAndWait(() -> new Launcher().show());
		Thread.sleep(setup ? 300 : 2500);
		if (setup) {
			SwingUtilities.invokeAndWait(() -> SHOWN.openSetup());
			Thread.sleep(400);
		}
		if ("tg".equals(mode)) {
			SwingUtilities.invokeAndWait(() -> {
				SHOWN.passBlock.setVisible(true);
				SHOWN.setTelegram(true, "https://t.me/");
				SHOWN.showError(TG_FIRST);
			});
			Thread.sleep(300);
		}
		SwingUtilities.invokeAndWait(() -> {
			Launcher one = SHOWN;
			JFrame shown = setup ? SETUP : one.frame;
			shown.setVisible(false);
			Component pane = shown.getContentPane();
			BufferedImage image = new BufferedImage(pane.getWidth(), pane.getHeight(),
					BufferedImage.TYPE_INT_RGB);
			Graphics g = image.getGraphics();
			pane.printAll(g);
			g.dispose();
			try {
				ImageIO.write(image, "png", new java.io.File(file));
			} catch (IOException broken) {
				System.out.println("не записалось: " + broken);
			}
		});
		System.exit(0);
	}

	/** Последнее открытое окно: нужно только съёмке. */
	private static Launcher SHOWN;
	private static JFrame SETUP;

	/** Открыть настройки: после сохранения папка игры может смениться. */
	private void openSetup() {
		Setup one = new Setup(frame, settings, () -> {
			root = Path.of(settings.getProperty("dir", Files2.home().toString()));
			save();
		});
		SETUP = one.show();
	}

	private void show() {
		SHOWN = this;
		load();
		root = Path.of(settings.getProperty("dir", Files2.home().toString()));
		frame = new JFrame("Пора Копать");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		// Без системной рамки: своя кнопка закрытия лежит прямо на картинке.
		frame.setUndecorated(true);
		frame.setSize(WIDTH + SIDE, HEIGHT);
		frame.setLocationRelativeTo(null);
		frame.setResizable(false);
		Image icon = image("icon.png");
		if (icon != null) {
			frame.setIconImage(icon);
		}

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(Skin.BG);
		body.setBorder(new EmptyBorder(2, PAD, 18, PAD));

		nick = Skin.text();
		password = Skin.secret();
		nick.setText(settings.getProperty("nick", ""));

		body.add(row(Skin.caption("Ник"), 16));
		body.add(Box.createVerticalStrut(5));
		body.add(capped(new Skin.Field(nick), 40));
		body.add(Box.createVerticalStrut(14));

		// Когда вход сохранён, поле прячем целиком: пустая строка «Пароль» выглядит так, будто
		// лаунчер каждый раз просит пароль заново.
		passBlock = new JPanel();
		passBlock.setLayout(new BoxLayout(passBlock, BoxLayout.Y_AXIS));
		passBlock.setBackground(Skin.BG);
		passBlock.add(row(Skin.caption("Пароль"), 16));
		passBlock.add(Box.createVerticalStrut(5));
		passBlock.add(capped(new Skin.Field(password), 40));
		passBlock.add(Box.createVerticalStrut(12));
		passBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, 73));
		body.add(passBlock);

		remember = new Skin.Check("Запомнить пароль", !saved().isEmpty());
		body.add(capped(remember, 20));
		body.add(Box.createVerticalStrut(16));

		play = new Skin.GoldButton("Играть", this::go);
		body.add(capped(play, 44));
		body.add(Box.createVerticalStrut(16));

		bar = new Skin.Bar();
		body.add(capped(bar, 6));
		body.add(Box.createVerticalStrut(9));

		status = Skin.label(" ", Skin.MUTED, Font.PLAIN, 12f);
		statusRow = row(status, STATUS_LINE);
		body.add(statusRow);
		crashBox = new JPanel();
		crashBox.setLayout(new BoxLayout(crashBox, BoxLayout.Y_AXIS));
		crashBox.setBackground(Skin.BG);
		crashBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		crashBox.setVisible(false);
		body.add(crashBox);
		body.add(tgRow());
		body.add(Box.createVerticalGlue());

		body.add(row(Skin.label((Site.viaMoscow() ? "ru.porakopatb.com · " : "porakopatb.com · ") + Update.running(),
				new java.awt.Color(0x5E6878), Font.PLAIN, 11f), 16));

		Skin.Header head = new Skin.Header(image("head.png"), WIDTH, HEADER);
		dragBy(head);

		JPanel left = new JPanel(new BorderLayout());
		left.setBackground(Skin.BG);
		left.setPreferredSize(new Dimension(WIDTH, HEIGHT));
		left.add(head, BorderLayout.NORTH);
		left.add(body, BorderLayout.CENTER);

		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(Skin.BG);
		outer.add(left, BorderLayout.WEST);
		outer.add(side(), BorderLayout.CENTER);
		frame.setContentPane(outer);

		// Кнопка гаснет, пока не введено и то и другое: меньше поводов увидеть отказ сайта.
		// С сохранённым входом пароль не нужен - там уже есть чем войти.
		Runnable check = () -> play.setOn(!working && !nick.getText().trim().isEmpty()
				&& (password.getPassword().length > 0 || !saved().isEmpty()));
		Skin.onType(nick, check);
		Skin.onType(password, check);
		// Снял галочку - забываем ключ сразу, не дожидаясь следующего входа, и просим пароль.
		remember.onChange(() -> {
			if (!remember.isOn() && !saved().isEmpty()) {
				settings.remove("device");
				save();
				askPassword();
			}
			check.run();
		});
		check.run();
		passBlock.setVisible(saved().isEmpty());

		password.addActionListener(e -> go());
		nick.addActionListener(e -> password.requestFocusInWindow());

		frame.setVisible(true);
		if (!nick.getText().isBlank() && saved().isEmpty()) {
			password.requestFocusInWindow();
		}
		// Новости и онлайн тянем после окна: сайт может не ответить, а играть это не мешает.
		new Thread(this::fillSide, "новости").start();
	}

	/** Правая колонка: новости, кто в игре и ссылки. */
	private JPanel side() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(Skin.PANEL);
		panel.setBorder(new EmptyBorder(10, 22, 18, 22));

		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Skin.PANEL);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JPanel keys = new JPanel();
		keys.setBackground(Skin.PANEL);
		keys.setLayout(new BoxLayout(keys, BoxLayout.X_AXIS));
		Skin.WinButton hide = new Skin.WinButton(false, () -> frame.setState(JFrame.ICONIFIED));
		Skin.WinButton close = new Skin.WinButton(true, () -> System.exit(0));
		hide.setMaximumSize(new Dimension(34, 26));
		close.setMaximumSize(new Dimension(34, 26));
		keys.add(hide);
		keys.add(Box.createHorizontalStrut(4));
		keys.add(close);
		bar.add(keys, BorderLayout.EAST);
		dragBy(bar);
		left(panel, bar);
		left(panel, Box.createVerticalStrut(8));

		// Афиша (01.10.2026): что будет на сервере - сверху, потому что это про ближайший час, а
		// новости подождут. Пока сайт не ответил, блока нет вовсе.
		soonBlock = new JPanel();
		soonBlock.setLayout(new BoxLayout(soonBlock, BoxLayout.Y_AXIS));
		soonBlock.setBackground(Skin.PANEL);
		soonBlock.setAlignmentX(0f);
		left(soonBlock, row(Skin.caption("Скоро"), 16, Skin.PANEL));
		left(soonBlock, Box.createVerticalStrut(4));
		soonBox = new JPanel();
		soonBox.setLayout(new BoxLayout(soonBox, BoxLayout.Y_AXIS));
		soonBox.setBackground(Skin.PANEL);
		soonBox.setAlignmentX(0f);
		left(soonBlock, soonBox);
		left(soonBlock, Box.createVerticalStrut(14));
		soonBlock.setVisible(false);
		left(panel, soonBlock);

		left(panel, row(Skin.caption("Новости"), 16, Skin.PANEL));
		left(panel, Box.createVerticalStrut(6));
		newsBox = new JPanel();
		newsBox.setLayout(new BoxLayout(newsBox, BoxLayout.Y_AXIS));
		newsBox.setBackground(Skin.PANEL);
		newsBox.setAlignmentX(0f);
		// Две строки на новость (заголовок и «дата · выжимка»), четыре новости.
		newsBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4 * 38 + 4));
		left(newsBox, row(Skin.label("Загружаю…", Skin.MUTED, Font.PLAIN, 12f), 20, Skin.PANEL));
		left(panel, newsBox);
		left(panel, Box.createVerticalStrut(18));

		left(panel, row(Skin.caption("Сейчас в игре"), 16, Skin.PANEL));
		left(panel, Box.createVerticalStrut(4));
		onlineLine = Skin.label(" ", Skin.MUTED, Font.PLAIN, 12f);
		left(panel, row(onlineLine, 20, Skin.PANEL));
		onlineBox = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
		onlineBox.setBackground(Skin.PANEL);
		onlineBox.setAlignmentX(0f);
		onlineBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
		onlineBox.setVisible(false);
		left(panel, onlineBox);

		left(panel, Box.createVerticalStrut(18));
		left(panel, row(Skin.caption("Игра"), 16, Skin.PANEL));
		left(panel, Box.createVerticalStrut(4));
		gameLine = Skin.label("…", Skin.MUTED, Font.PLAIN, 12f);
		left(panel, row(gameLine, 18, Skin.PANEL));
		packLine = Skin.label(" ", Skin.MUTED, Font.PLAIN, 12f);
		left(panel, row(packLine, 18, Skin.PANEL));

		left(panel, Box.createVerticalGlue());
		left(panel, link("Настройки", this::openSetup));
		left(panel, link("Регистрация", () -> open(browserBase() + "/register")));
		left(panel, link("Забыл пароль", () -> open(browserBase() + "/forgot")));
		left(panel, link("Вики сервера", () -> open(browserBase() + "/wiki")));
		left(panel, link("Карта мира", () -> open(browserBase() + "/map")));
		left(panel, link("Форум", () -> open(browserBase() + "/forum")));
		return panel;
	}

	/** Всё в колонке прижато к левому краю: BoxLayout иначе разъезжается. */
	private static void left(JPanel to, java.awt.Component what) {
		if (what instanceof JComponent piece) {
			piece.setAlignmentX(0f);
		}
		to.add(what);
	}

	private Skin.Link link(String text, Runnable action) {
		Skin.Link out = new Skin.Link(text, 13f, false, action);
		out.setAlignmentX(0f);
		out.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		return out;
	}

	/** Открыть страницу в браузере игрока. */
	private static void open(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception broken) {
            // Нет браузера по умолчанию - молчим: это не повод мешать игре.
        }
	}

	/**
	 * Скачана ли игра и сколько она занимает. По этой строке видно, ждать ли первого запуска
	 * долго: гигабайт по слабому каналу - это минуты, и лучше знать заранее.
	 */
	private void tellAboutGame() {
		boolean ready = Files.isDirectory(root.resolve("versions"));
		long bytes = 0;
		if (ready) {
			try (var walk = Files.walk(root)) {
				bytes = walk.filter(Files::isRegularFile).mapToLong(file -> {
					try {
						return Files.size(file);
					} catch (IOException skip) {
						return 0;
					}
				}).sum();
			} catch (Exception quiet) {
				bytes = 0;
			}
		}
		double gb = Math.round(bytes / 1024.0 / 1024 / 102.4) / 10.0;
		String text = bytes > 0 ? "скачана, " + gb + " ГБ" : "ещё не скачана";
		String more = bytes > 0 ? " " : "первый запуск займёт несколько минут";
		SwingUtilities.invokeLater(() -> {
			gameLine.setText(text);
			if (!more.isBlank()) {
				packLine.setText(more);
			}
		});
	}

	/**
	 * Адрес сайта для браузера - тот, что прислал сайт в ссылке «Регистрация» (09.10.2026).
	 *
	 * Это не всегда {@link Site#base()}: Ростелеком рвёт в браузере всё, что зовётся porakopatb.com,
	 * хотя сам лаунчер (Java) проходит. Сайт по адресу спросившего отдаёт игрокам из России ссылки на
	 * porakopat.com - тот же сайт под другим именем. Пока сайт не ответил - прежний адрес.
	 */
	private static volatile String browserBase;
	private static final java.util.Set<String> BROWSER_BASES = java.util.Set.of(
			"https://porakopatb.com", "https://ru.porakopatb.com", "https://porakopat.com");

	private static String browserBase() {
		String known = browserBase;
		return known != null ? known : Site.base();
	}

	private void fillSide() {
		tellAboutGame();
		Site.Home home = Site.home();
		if (home != null && home.register() != null && home.register().endsWith("/register")) {
			String base = home.register().substring(0, home.register().length() - "/register".length());
			// Только наши имена: адрес из ответа открывается в браузере, чужой сюда пускать незачем.
			if (BROWSER_BASES.contains(base)) {
				browserBase = base;
			}
		}
		String pack = packWhen();
		SwingUtilities.invokeLater(() -> {
			newsBox.removeAll();
			if (!pack.isEmpty()) {
				packLine.setText("сборка от " + pack);
			}
			if (home == null) {
				left(newsBox, row(Skin.label("Сайт не ответил", Skin.MUTED, Font.PLAIN, 12f), 20, Skin.PANEL));
				onlineLine.setText("неизвестно");
			} else {
				soon = home.soon();
				fillSoon();
				if (soonTimer == null) {
					// «Через 40 мин» должно убывать, пока окно открыто: пересчёт по уже пришедшей
					// афише, без нового запроса к сайту.
					soonTimer = new Timer(30_000, tick -> fillSoon());
					soonTimer.start();
				}
				if (home.news().isEmpty()) {
					left(newsBox, row(Skin.label("Пока тихо", Skin.MUTED, Font.PLAIN, 12f), 20, Skin.PANEL));
				}
				// Афиша заняла место - новостей на одну меньше: окно не резиновое.
				int most = soonBlock.isVisible() ? 3 : 4;
				int shown = 0;
				for (Site.News one : home.news()) {
					if (shown++ >= most) {
						break;
					}
					Skin.Link item = new Skin.Link(one.title(), 13f, false, () -> open(one.url()));
					item.setAlignmentX(0f);
					item.setMaximumSize(new Dimension(SIDE - 44, 22));
					left(newsBox, item);
					// Под заголовком - когда вышла и первая строка: по ней видно, стоит ли открывать.
					String under = newsUnder(one);
					if (!under.isEmpty()) {
						JLabel line = Skin.label(under, Skin.MUTED, Font.PLAIN, 11f);
						line.setMaximumSize(new Dimension(SIDE - 44, 16));
						left(newsBox, line);
					}
				}
				onlineLine.setText(text(home));
				fillOnline(home);
			}
			newsBox.revalidate();
			newsBox.repaint();
		});
	}

	/**
	 * Строки афиши: «через 40 мин · Вождь огров выходит в логово». Остаток считается по часам
	 * игрока от момента начала, а не берётся готовой строкой сайта: окно бывает открыто подолгу, и
	 * у игрока свой пояс. Прошедшее отбрасывается; щелчок ведёт на страницу событий.
	 */
	private void fillSoon() {
		soonBox.removeAll();
		long now = System.currentTimeMillis();
		int shown = 0;
		for (Site.Soon one : soon) {
			String lead = lead(one, now);
			if (lead == null) {
				continue;
			}
			if (shown++ >= SOON_LINES) {
				break;
			}
			Skin.Link item = new Skin.Link(lead + " · " + one.title(), 12.5f, false, () -> open(Site.base() + "/events"));
			item.setToolTipText(tip(one));
			item.setAlignmentX(0f);
			item.setMaximumSize(new Dimension(SIDE - 44, 20));
			left(soonBox, item);
		}
		boolean was = soonBlock.isVisible();
		soonBlock.setVisible(shown > 0);
		soonBox.revalidate();
		soonBox.repaint();
		if (was != soonBlock.isVisible()) {
			soonBlock.getParent().revalidate();
		}
	}

	/** Левая часть строки: «через 40 мин», «ещё 35 мин», «сейчас», «15:00–01:00»; null - уже прошло. */
	private static String lead(Site.Soon one, long now) {
		return switch (one.state()) {
			case "live" -> one.until() > 0 && one.until() <= now ? null
					: one.until() > now ? "ещё " + span(one.until() - now) : "сейчас";
			case "window" -> one.until() <= now ? null
					: now >= one.at() ? "до " + clock(one.until())
					: (sameDay(one.at(), now) ? "" : "завтра ") + clock(one.at()) + "–" + clock(one.until());
			default -> one.at() <= now - 5 * 60_000L ? null
					: one.at() > now ? "через " + span(one.at() - now) : "сейчас";
		};
	}

	/** Остаток словами, вверх до минуты: «40 мин», «2 ч 15 мин», «3 дн». */
	private static String span(long ms) {
		long minutes = Math.max(1L, (ms + 59_999L) / 60_000L);
		if (minutes >= 48 * 60) {
			return minutes / (24 * 60) + " дн";
		}
		if (minutes >= 60) {
			return minutes % 60 == 0 ? minutes / 60 + " ч" : minutes / 60 + " ч " + minutes % 60 + " мин";
		}
		return minutes + " мин";
	}

	private static java.time.ZonedDateTime local(long at) {
		return java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault());
	}

	private static String clock(long at) {
		java.time.ZonedDateTime when = local(at);
		return String.format("%02d:%02d", when.getHour(), when.getMinute());
	}

	private static boolean sameDay(long at, long now) {
		return local(at).toLocalDate().equals(local(now).toLocalDate());
	}

	/** Подсказка над строкой: название целиком, где, и когда - по часам этого компьютера. */
	private static String tip(Site.Soon one) {
		StringBuilder out = new StringBuilder("<html><b>").append(html(one.title())).append("</b>");
		if (!one.where().isEmpty()) {
			out.append("<br>").append(html(one.where()));
		}
		java.time.ZonedDateTime start = local(one.at());
		String day = String.format("%02d.%02d %s", start.getDayOfMonth(), start.getMonthValue(), clock(one.at()));
		switch (one.state()) {
			case "live" -> {
				if (one.until() > 0) {
					out.append("<br>до ").append(clock(one.until()));
				}
			}
			case "window" -> out.append("<br>").append(day).append("–").append(clock(one.until()))
					.append(", минуту город выберет сам");
			default -> out.append("<br>начало: ").append(day);
		}
		return out.append("<br>Щелчок - всё расписание на сайте</html>").toString();
	}

	private static String html(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Когда собран пак: версия приходит временем последней правки вида 20260914090330. */
	private static String packWhen() {
		try {
			String when = Site.pack().version();
			return when.length() >= 8
					? when.substring(6, 8) + "." + when.substring(4, 6) + "." + when.substring(0, 4)
					: "";
		} catch (Exception quiet) {
			return "";
		}
	}

	/** «17.09 · Кремний варится только как в оригинале…» - дата и начало текста, обрезанные по ширине. */
	private static String newsUnder(Site.News one) {
		StringBuilder under = new StringBuilder();
		if (one.at() > 0) {
			java.time.LocalDate day = java.time.Instant.ofEpochMilli(one.at()).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
			under.append(String.format("%02d.%02d", day.getDayOfMonth(), day.getMonthValue()));
		}
		String snippet = one.snippet() == null ? "" : one.snippet().replaceAll("\\s+", " ").trim();
		if (!snippet.isEmpty()) {
			if (under.length() > 0) {
				under.append(" · ");
			}
			under.append(snippet.length() > 48 ? snippet.substring(0, 47) + "…" : snippet);
		}
		return under.toString();
	}

	/** Лица и ники тех, кто в игре: до восьми, дальше только число в строке выше. */
	private void fillOnline(Site.Home home) {
		onlineBox.removeAll();
		boolean faces = home.online() && !home.players().isEmpty() && home.players().size() <= 8;
		onlineBox.setVisible(faces);
		if (faces) {
			for (String nick : home.players()) {
				JLabel who = new JLabel(nick);
				who.setForeground(Skin.TEXT);
				who.setFont(Skin.font(Font.PLAIN, 12f));
				who.setIconTextGap(5);
				who.setIcon(Faces.of(nick, icon -> {
					who.setIcon(icon);
					who.repaint();
				}));
				onlineBox.add(who);
			}
		}
		onlineBox.revalidate();
		onlineBox.repaint();
	}

	/** Строка про онлайн: сколько людей и кто именно, если их немного. */
	private static String text(Site.Home home) {
		if (!home.online()) {
			return "сервер спит";
		}
		if (home.players().isEmpty()) {
			return "никого, будь первым";
		}
		// Ники теперь стоят лицами в строке ниже; здесь - только число, когда их мало.
		String who = String.join(", ", home.players());
		return home.players().size() > 8 ? home.players().size() + " · " + who : home.players().size() + " в игре";
	}

	/** Показать поле пароля и поставить в него курсор: сохранённый вход больше не годится. */
	private void askPassword() {
		if (passBlock.isVisible()) {
			return;
		}
		passBlock.setVisible(true);
		passBlock.revalidate();
		passBlock.repaint();
		password.requestFocusInWindow();
	}

	/** Окно без рамки само не таскается - возим его за шапку. */
	private void dragBy(Component what) {
		java.awt.event.MouseAdapter hand = new java.awt.event.MouseAdapter() {
			private java.awt.Point grab;

			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				grab = e.getPoint();
			}

			@Override
			public void mouseDragged(java.awt.event.MouseEvent e) {
				if (grab == null) {
					return;
				}
				java.awt.Point at = frame.getLocation();
				frame.setLocation(at.x + e.getX() - grab.x, at.y + e.getY() - grab.y);
			}
		};
		what.addMouseListener(hand);
		what.addMouseMotionListener(hand);
	}

	/** BoxLayout растягивает всё по высоте, поэтому ростом каждой полосы правим вручную. */
	private static Component capped(Component what, int height) {
		what.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		what.setPreferredSize(new Dimension(0, height));
		return what;
	}

	/** Строка во всю ширину: иначе подпись уезжает в центр. */
	private static JPanel row(Component what, int height) {
		return row(what, height, Skin.BG);
	}

	private static JPanel row(Component what, int height, java.awt.Color back) {
		JPanel line = new JPanel(new BorderLayout());
		line.setBackground(back);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		line.setPreferredSize(new Dimension(0, height));
		line.add(what, BorderLayout.WEST);
		return line;
	}

	/** Картинка из ресурсов рядом с классом; если её нет, окно просто останется без неё. */
	private static Image image(String name) {
		try (var in = Launcher.class.getResourceAsStream(name)) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException missing) {
			return null;
		}
	}

	/**
	 * Игра закрылась сразу после запуска. Причина почти всегда в логе, поэтому не пересказываем
	 * её своими словами, а даём открыть лог одним нажатием.
	 */
	private void crashed(int code) {
		plain("Игра закрылась сразу (код " + code + ")");
		if (crashLink == null) {
			crashLink = new Skin.Link("Показать лог игры", 12f, false,
					() -> Files2.reveal(root.resolve("game.log")));
			crashLink.setAlignmentX(0f);
			crashLink.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
			crashBox.add(crashLink);
		}
		crashBox.setVisible(true);
		crashBox.revalidate();
		crashBox.repaint();
	}

	/** Ключ «запомнить пароль» для этой машины; пустая строка, если его нет. */
	private String saved() {
		return settings.getProperty("device", "");
	}

	private void go() {
		String who = nick.getText().trim();
		String secret = new String(password.getPassword());
		String key = saved();
		if (who.isEmpty() || (secret.isEmpty() && key.isEmpty())) {
			say("Введи ник и пароль.");
			return;
		}
		working = true;
		play.setOn(false);
		bar.setVisible(true);
		bar.spin();
		spinner = new Timer(40, e -> bar.repaint());
		spinner.start();
		say("Вхожу");

		new Thread(() -> {
			try {
				// Пароль набран - идём по нему: игрок мог сменить учётную запись.
				Site.Account account = secret.isEmpty()
						? Site.loginSaved(key)
						: Site.login(who, secret, remember.isOn());
				long askedAt = System.currentTimeMillis();
				settings.setProperty("nick", account.nick());
				if (!account.device().isEmpty()) {
					settings.setProperty("device", account.device());
				}
				save();
				// Без Телеграма сервер не пустит (01.10.2026) - кнопка сразу, а игра ставится дальше:
				// пока качается гигабайт, как раз есть время привязать.
				SwingUtilities.invokeLater(() -> setTelegram(account.needTg(), account.tgLink()));

				Site.Pack pack = Site.pack();
				SwingUtilities.invokeLater(() -> {
					spinner.stop();
					bar.set(0);
				});
				Installer.Plan plan = new Installer(root).install(pack, account.packKey(), (what, done) ->
						SwingUtilities.invokeLater(() -> {
							plain(what);
							bar.set(done);
						}));

				// Игру, которую сервер не пустит, не запускаем: человек ждал бы минуту загрузки ради
				// отказа на входе - ровно то, от чего эта проверка. Пока шла установка, он мог уже
				// привязать, поэтому спрашиваем сайт ещё раз; если с ответа прошло несколько секунд.
				Site.Account player = account;
				if (player.needTg() && System.currentTimeMillis() - askedAt > RECHECK_AFTER_MS) {
					say("Проверяю Телеграм");
					player = secret.isEmpty() ? Site.loginSaved(key) : Site.login(who, secret, false);
					Site.Account fresh = player;
					SwingUtilities.invokeLater(() -> setTelegram(fresh.needTg(), fresh.tgLink()));
				}
				if (player.needTg()) {
					SwingUtilities.invokeLater(() -> {
						working = false;
						bar.setVisible(false);
						play.setOn(true);
						showError(TG_FIRST);
					});
					return;
				}

				say("Запускаю игру");
				// Моды расшифровываются сюда и живут ровно столько, сколько идёт игра.
				Path unpacked = Vault.unpack(pack.files(), player.packKey());
				Process game = Game.start(root, plan, player, pack.minecraft(), pack.fabric(),
						memory(), unpacked);
				SwingUtilities.invokeLater(() -> frame.setVisible(false));
				// Игра встаёт на ноги секунд десять. Если она умерла за это время - это не запуск,
				// а падение, и игроку надо показать окно обратно, иначе он остаётся ни с чем.
				if (!game.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
					// Игра поднялась. Лаунчер не уходит совсем, а тихо ждёт её конца, чтобы стереть
					// расшифрованные моды. Уйди он сразу - открытые джарники остались бы лежать во
					// временной папке до следующего запуска, а ради того, чтобы их там не было, всё
					// и затевалось. Окно к этому времени уже скрыто, так что для игрока он исчез.
					game.waitFor();
					Vault.erase(unpacked);
					System.exit(0);
				}
				Vault.erase(unpacked);
				int code = game.exitValue();
				SwingUtilities.invokeLater(() -> {
					working = false;
					bar.setVisible(false);
					play.setOn(true);
					frame.setVisible(true);
					crashed(code);
				});
				return;
			} catch (Exception broken) {
				String message = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				// Ключ протух или его отозвали сменой пароля - выбрасываем и просим пароль.
				if (message.contains("Сохранённый вход")) {
					settings.remove("device");
					save();
					SwingUtilities.invokeLater(() -> {
						remember.set(false);
						askPassword();
					});
				}
				SwingUtilities.invokeLater(() -> {
					if (spinner != null) {
						spinner.stop();
					}
					working = false;
					bar.setVisible(false);
					play.setOn(true);
					// Ошибка в несколько строк и строка Телеграма вместе в окно не влезают; ошибка
					// важнее, а кнопка вернётся со следующим «Играть».
					setTelegram(false, "");
					showError(message);
				});
			}
		}, "поехали").start();
	}

	/** Сколько памяти отдать игре: половина от машины, но не меньше двух и не больше восьми. */
	private int memory() {
		String saved = settings.getProperty("memory");
		if (saved != null) {
			try {
				return Integer.parseInt(saved);
			} catch (NumberFormatException ignored) {
				// Настройка испорчена - считаем сами.
			}
		}
		long total = 4096;
		try {
			com.sun.management.OperatingSystemMXBean os =
					(com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
							.getOperatingSystemMXBean();
			total = os.getTotalMemorySize() / 1024 / 1024;
		} catch (Throwable ignored) {
			// Не вышло спросить систему - остаёмся на четырёх гигабайтах.
		}
		return (int) Math.max(2048, Math.min(8192, total / 2));
	}

	private void say(String what) {
		SwingUtilities.invokeLater(() -> plain(what));
	}

	/** Высота строки под полосой, когда в ней одна строка текста. */
	private static final int STATUS_LINE = 18;
	/**
	 * Ширина, на которой переносится длинная ошибка. Тело окна - 420 точек, но HTML в Swing читает
	 * px как пункты и растягивает в 1.3 раза: 410 выходили в 533 точки, за край окна. 310 - это 403
	 * точки на экране (замер шрифтом Segoe UI 12, 01.10.2026).
	 */
	private static final int STATUS_WRAP = 310;
	private JPanel statusRow;

	/** Обычная строка хода дел - в одну строку, как была. */
	private void plain(String what) {
		status.setText(what);
		fitStatus(STATUS_LINE);
	}

	/**
	 * Ошибка целиком, в несколько строк (01.10.2026). Отказ «третий аккаунт» с советом написать
	 * администрации не влезал в одну строку и обрезался на полуслове - а совет и есть то, ради чего
	 * его читают.
	 */
	private void showError(String message) {
		String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		status.setText("<html><div style='width:" + STATUS_WRAP + "px'>" + safe + "</div></html>");
		fitStatus(Math.max(STATUS_LINE, status.getPreferredSize().height));
	}

	/**
	 * Что сказать, когда игра поставлена, а Телеграм так и не привязан. Короткое нарочно: строка с
	 * кнопкой стоит прямо под ним, и вместе с полем пароля в окно влезает одна строка текста, не две.
	 */
	static final String TG_FIRST = "Сначала привяжи Телеграм кнопкой ниже, потом снова «Играть».";
	/** Перепроверять Телеграм перед запуском, только если с ответа сайта прошло больше этого. */
	private static final long RECHECK_AFTER_MS = 5000;

	/**
	 * Строка «Привязать Телеграм» (01.10.2026). Раньше новичок узнавал, что Телеграм обязателен, только
	 * когда сервер выбрасывал его из уже скачанной и запущенной игры. Теперь сайт говорит это на входе
	 * в лаунчер (need_tg), и кнопка открывает бота сразу с кодом привязки - бот привяжет по «Запустить».
	 */
	private JPanel tgRow() {
		tgBox = new JPanel();
		tgBox.setLayout(new BoxLayout(tgBox, BoxLayout.X_AXIS));
		tgBox.setBackground(Skin.BG);
		tgBox.setBorder(new EmptyBorder(6, 0, 0, 0));
		tgBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		tgBox.setPreferredSize(new Dimension(0, 34));
		tgBox.add(new Skin.LineButton("Привязать Телеграм", this::openTelegram));
		tgBox.add(Box.createHorizontalStrut(10));
		tgBox.add(Skin.label("без него сервер не пустит в игру", Skin.MUTED, Font.PLAIN, 12f));
		tgBox.add(Box.createHorizontalGlue());
		tgBox.setVisible(false);
		return tgBox;
	}

	/**
	 * Показать или спрятать строку Телеграма. Ссылку берём только в бота Телеграма: всё прочее (сайт
	 * без поля, странный ответ) ведёт в профиль на сайте - там та же кнопка.
	 */
	private void setTelegram(boolean need, String link) {
		if (need) {
			tgLinkNow = link != null && link.startsWith(TG_LINK) ? link : profileLink();
			tgLinkAt = System.currentTimeMillis();
			// Строка про упавшую игру тут лишняя: мешает сейчас Телеграм, а не она.
			crashBox.setVisible(false);
		}
		tgBox.setVisible(need);
		tgBox.getParent().revalidate();
		tgBox.getParent().repaint();
	}

	/** Свой профиль на сайте: там та же кнопка «Привязать Телеграм». */
	private String profileLink() {
		String nick = settings.getProperty("nick", "");
		return Site.base() + (nick.isEmpty() ? "/login" : "/u/" + nick + "#tg");
	}

	/** Только такие ссылки из ответа сайта открываем как есть: бот Телеграма, и ничего другого. */
	private static final String TG_LINK = "https://t.me/";
	/**
	 * Сколько ссылке с кодом верить без перепроверки. Сайт отдаёт код, которому жить ещё не меньше
	 * пяти минут; минута - запас на дорогу ответа и на то, чтобы игрок дошёл до «Запустить» в боте.
	 */
	private static final long TG_LINK_FRESH_MS = 4 * 60_000;

	/**
	 * Кнопка «Привязать Телеграм». Код в ссылке живёт четверть часа, а гигабайт игры по слабому
	 * каналу качается дольше - и кнопка, нажатая под конец установки, открывала бы протухший код.
	 * Бот отвечал «не подошёл, нажми кнопку ещё раз», та открывала тот же код, и так по кругу.
	 * Поэтому старую ссылку не открываем, а сперва спрашиваем у сайта свежую - тем же входом, что
	 * и «Играть»; не вышло - открываем профиль на сайте, там код заводится по нажатию.
	 */
	private void openTelegram() {
		String link = tgLinkNow;
		if (!link.startsWith(TG_LINK) || System.currentTimeMillis() - tgLinkAt < TG_LINK_FRESH_MS) {
			open(link);
			return;
		}
		if (tgAsking) {
			return;
		}
		String who = nick.getText().trim();
		String secret = new String(password.getPassword());
		String key = saved();
		if (who.isEmpty() || (secret.isEmpty() && key.isEmpty())) {
			open(profileLink());
			return;
		}
		tgAsking = true;
		new Thread(() -> {
			Site.Account account = null;
			try {
				account = secret.isEmpty() ? Site.loginSaved(key) : Site.login(who, secret, false);
			} catch (Exception broken) {
				// Сайт не ответил или вход не принят - дорога через профиль остаётся.
			}
			Site.Account fresh = account;
			SwingUtilities.invokeLater(() -> {
				tgAsking = false;
				if (fresh == null) {
					open(profileLink());
				} else if (fresh.needTg()) {
					setTelegram(true, fresh.tgLink());
					open(tgLinkNow);
				} else {
					// Пока качалось, он уже привязал - открывать нечего, можно играть.
					setTelegram(false, "");
					if (!working) {
						plain("Телеграм привязан - жми «Играть».");
					}
				}
			});
		}, "телеграм").start();
	}

	private void fitStatus(int height) {
		statusRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		statusRow.setPreferredSize(new Dimension(0, height));
		statusRow.revalidate();
		statusRow.repaint();
	}

	private void load() {
		Path file = Files2.home().resolve("launcher.properties");
		if (Files.isRegularFile(file)) {
			try (var in = Files.newInputStream(file)) {
				settings.load(in);
			} catch (IOException ignored) {
				// Настройки - удобство, а не необходимость.
			}
		}
	}

	private void save() {
		try {
			Files.createDirectories(Files2.home());
			try (var out = Files.newOutputStream(Files2.home().resolve("launcher.properties"))) {
				settings.store(out, "Пора Копать");
			}
		} catch (IOException ignored) {
			// Не записались настройки - ничего страшного, ник просто спросим снова.
		}
	}
}
