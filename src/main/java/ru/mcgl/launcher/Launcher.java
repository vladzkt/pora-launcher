package ru.mcgl.launcher;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Лаунчер «Пора Копать».
 *
 * Одно окно: ник, пароль, кнопка. Всё остальное - скачивание игры, Fabric и модов - он делает
 * сам и показывает полосой. Пароль никуда не сохраняется, ник запоминается ради удобства.
 *
 * Для проверки без окна: {@code --check} печатает список сборки, {@code --install} доводит
 * установку до конца и ничего не запускает.
 */
public final class Launcher {

	private static final Color BG = new Color(0x0B0D11);
	private static final Color PANEL = new Color(0x141A23);
	private static final Color LINE = new Color(0x28313F);
	private static final Color TEXT = new Color(0xE2E6EE);
	private static final Color MUTED = new Color(0x8B95A8);
	private static final Color GOLD = new Color(0xF0B53F);

	private final Path root = Files2.home();
	private final Properties settings = new Properties();

	private JFrame frame;
	private JTextField nick;
	private JPasswordField password;
	private JButton play;
	private JProgressBar bar;
	private JLabel status;

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && ("--check".equals(args[0]) || "--install".equals(args[0]))) {
			console(args);
			return;
		}
		SwingUtilities.invokeLater(() -> new Launcher().show());
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
		System.out.println("Команда запуска собралась: "
				+ Game.command(root, plan, new Site.Account("Проба", "нет", ""),
						pack.minecraft(), pack.fabric(), 4096).size() + " частей");
	}

	private void show() {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception ignored) {
			// Внешний вид системы - мелочь, без него тоже работает.
		}
		load();

		frame = new JFrame("Пора Копать");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(420, 330);
		frame.setLocationRelativeTo(null);
		frame.setResizable(false);

		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(BG);
		panel.setBorder(BorderFactory.createEmptyBorder(22, 26, 18, 26));
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;

		JLabel title = new JLabel("ПОРА КОПАТЬ");
		title.setForeground(GOLD);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
		c.gridy = 0;
		c.insets = new Insets(0, 0, 2, 0);
		panel.add(title, c);

		JLabel sub = new JLabel("Гриф-сервер Minecraft 1.21.1");
		sub.setForeground(MUTED);
		c.gridy = 1;
		c.insets = new Insets(0, 0, 18, 0);
		panel.add(sub, c);

		nick = field(new JTextField(settings.getProperty("nick", "")));
		c.gridy = 2;
		c.insets = new Insets(0, 0, 8, 0);
		panel.add(labelled("Ник", nick), c);

		password = new JPasswordField();
		style(password);
		c.gridy = 3;
		panel.add(labelled("Пароль", password), c);

		play = new JButton("Играть");
		play.setBackground(GOLD);
		play.setForeground(new Color(0x2A1C02));
		play.setFocusPainted(false);
		play.setFont(play.getFont().deriveFont(Font.BOLD, 15f));
		play.setPreferredSize(new Dimension(0, 38));
		play.addActionListener(e -> go());
		c.gridy = 4;
		c.insets = new Insets(12, 0, 10, 0);
		panel.add(play, c);

		bar = new JProgressBar(0, 1000);
		bar.setVisible(false);
		bar.setBackground(PANEL);
		bar.setForeground(GOLD);
		bar.setBorderPainted(false);
		c.gridy = 5;
		c.insets = new Insets(0, 0, 6, 0);
		panel.add(bar, c);

		status = new JLabel(" ");
		status.setForeground(MUTED);
		c.gridy = 6;
		c.insets = new Insets(0, 0, 0, 0);
		panel.add(status, c);

		c.gridy = 7;
		c.weighty = 1;
		panel.add(Box.createVerticalGlue(), c);

		frame.setContentPane(panel);
		frame.getRootPane().setDefaultButton(play);
		frame.setVisible(true);
		if (!nick.getText().isBlank()) {
			password.requestFocusInWindow();
		}
	}

	private JPanel labelled(String name, javax.swing.JComponent field) {
		JPanel box = new JPanel(new BorderLayout(0, 3));
		box.setBackground(BG);
		JLabel label = new JLabel(name);
		label.setForeground(MUTED);
		label.setFont(label.getFont().deriveFont(11f));
		box.add(label, BorderLayout.NORTH);
		box.add(field, BorderLayout.CENTER);
		return box;
	}

	private JTextField field(JTextField f) {
		style(f);
		return f;
	}

	private void style(javax.swing.text.JTextComponent f) {
		f.setBackground(PANEL);
		f.setForeground(TEXT);
		f.setCaretColor(TEXT);
		f.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(7, 9, 7, 9)));
	}

	private void go() {
		String who = nick.getText().trim();
		String secret = new String(password.getPassword());
		if (who.isEmpty() || secret.isEmpty()) {
			say("Введи ник и пароль.");
			return;
		}
		play.setEnabled(false);
		bar.setVisible(true);
		bar.setIndeterminate(true);
		say("Вхожу");

		new Thread(() -> {
			try {
				Site.Account account = Site.login(who, secret);
				settings.setProperty("nick", account.nick());
				save();

				Site.Pack pack = Site.pack();
				SwingUtilities.invokeLater(() -> bar.setIndeterminate(false));
				Installer.Plan plan = new Installer(root).install(pack, (what, done) ->
						SwingUtilities.invokeLater(() -> {
							status.setText(what);
							bar.setValue((int) (done * 1000));
						}));

				say("Запускаю игру");
				Game.start(root, plan, account, pack.minecraft(), pack.fabric(), memory());
				SwingUtilities.invokeLater(() -> frame.setVisible(false));
				// Даём игре встать на ноги и уходим: держать окно лаунчера незачем.
				Thread.sleep(8000);
				System.exit(0);
			} catch (Exception broken) {
				String message = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				SwingUtilities.invokeLater(() -> {
					bar.setVisible(false);
					bar.setIndeterminate(false);
					play.setEnabled(true);
					status.setText(message);
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
		SwingUtilities.invokeLater(() -> status.setText(what));
	}

	private void load() {
		Path file = root.resolve("launcher.properties");
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
			Files.createDirectories(root);
			try (var out = Files.newOutputStream(root.resolve("launcher.properties"))) {
				settings.store(out, "Пора Копать");
			}
		} catch (IOException ignored) {
			// Не записались настройки - ничего страшного, ник просто спросим снова.
		}
	}
}
