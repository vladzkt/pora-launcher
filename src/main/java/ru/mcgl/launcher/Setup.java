package ru.mcgl.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * Окно настроек: память, папка с игрой и лог.
 *
 * Памяти по умолчанию лаунчер берёт половину от машины, и обычно этого хватает. Менять её
 * приходится в двух случаях: у человека много оперативки и он хочет дальнюю прорисовку, либо
 * наоборот - машина слабая и игре лучше дать поменьше, чем уронить систему.
 *
 * Папка нужна тем, у кого забит системный диск: игра с ресурсами занимает больше гигабайта.
 */
final class Setup {

	/** Сколько памяти можно выбрать, в мегабайтах. Ноль - считать самим. */
	private static final int[] SIZES = { 0, 2048, 3072, 4096, 6144, 8192, 12288, 16384 };

	private final Properties settings;
	private final Runnable onSave;
	private final JFrame parent;

	private JFrame frame;
	private JLabel dirLine;
	private int chosen;
	private Path dir;

	Setup(JFrame parent, Properties settings, Runnable onSave) {
		this.parent = parent;
		this.settings = settings;
		this.onSave = onSave;
	}

	JFrame show() {
		chosen = number(settings.getProperty("memory", "0"));
		dir = Path.of(settings.getProperty("dir", Files2.home().toString()));

		frame = new JFrame("Настройки");
		frame.setUndecorated(true);
		frame.setSize(470, 346);
		frame.setLocationRelativeTo(parent);
		frame.setResizable(false);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(Skin.BG);
		body.setBorder(new EmptyBorder(16, 26, 20, 26));

		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(Skin.BG);
		top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		top.add(Skin.label("Настройки", Skin.GOLD, Font.BOLD, 16f), BorderLayout.WEST);
		top.add(new Skin.WinButton(true, () -> frame.dispose()), BorderLayout.EAST);
		add(body, top);
		add(body, Box.createVerticalStrut(14));

		add(body, line(Skin.caption("Память для игры")));
		add(body, Box.createVerticalStrut(6));
		JPanel sizes = new JPanel();
		sizes.setLayout(new BoxLayout(sizes, BoxLayout.X_AXIS));
		sizes.setBackground(Skin.BG);
		sizes.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		List<Skin.Pill> pills = new ArrayList<>();
		for (int size : SIZES) {
			Skin.Pill pill = new Skin.Pill(name(size), size == chosen, () -> {
			});
			pills.add(pill);
			sizes.add(pill);
			sizes.add(Box.createHorizontalStrut(4));
		}
		for (int i = 0; i < SIZES.length; i++) {
			int size = SIZES[i];
			Skin.Pill pill = pills.get(i);
			pill.onPick(() -> {
				chosen = size;
				for (Skin.Pill other : pills) {
					other.setOn(other == pill);
				}
			});
		}
		add(body, sizes);
		add(body, Box.createVerticalStrut(6));
		add(body, line(Skin.label("«Авто» берёт половину памяти машины. Больше - дальше",
				Skin.MUTED, Font.PLAIN, 11.5f)));
		add(body, line(Skin.label("прорисовка, но и нагрузка на компьютер выше.",
				Skin.MUTED, Font.PLAIN, 11.5f)));
		add(body, Box.createVerticalStrut(18));

		add(body, line(Skin.caption("Папка с игрой")));
		add(body, Box.createVerticalStrut(6));
		dirLine = Skin.label(dir.toString(), Skin.TEXT, Font.PLAIN, 12f);
		add(body, line(dirLine));
		add(body, Box.createVerticalStrut(8));
		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.setBackground(Skin.BG);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		buttons.add(new Skin.Link("Открыть папку", 12.5f, false, () -> Files2.reveal(dir)));
		buttons.add(Box.createHorizontalStrut(18));
		buttons.add(new Skin.Link("Выбрать другую", 12.5f, false, this::pickDir));
		buttons.add(Box.createHorizontalStrut(18));
		buttons.add(new Skin.Link("Показать лог игры", 12.5f, false,
				() -> Files2.reveal(dir.resolve("game.log"))));
		buttons.add(Box.createHorizontalGlue());
		add(body, buttons);

		add(body, Box.createVerticalGlue());
		Skin.GoldButton save = new Skin.GoldButton("Сохранить", this::save);
		save.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		save.setPreferredSize(new Dimension(0, 40));
		add(body, save);

		frame.setContentPane(body);
		frame.setVisible(true);
		return frame;
	}

	/** Выравнивание у всех детей одно: BoxLayout иначе сводит их к общей оси и разъезжается. */
	private static void add(JPanel to, Component what) {
		if (what instanceof JComponent piece) {
			piece.setAlignmentX(0f);
		}
		to.add(what);
	}

	private static JPanel line(Component what) {
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(Skin.BG);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		row.setPreferredSize(new Dimension(0, 18));
		row.add(what, BorderLayout.WEST);
		return row;
	}

	private static String name(int mb) {
		return mb == 0 ? "Авто" : (mb / 1024) + " ГБ";
	}

	private static int number(String what) {
		try {
			return Integer.parseInt(what);
		} catch (NumberFormatException broken) {
			return 0;
		}
	}

	private void pickDir() {
		JFileChooser chooser = new JFileChooser(dir.toFile());
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Где держать игру");
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			dir = chooser.getSelectedFile().toPath();
			dirLine.setText(dir.toString());
		}
	}

	/**
	 * Старую папку не трогаем и ничего не переносим: игра просто скачается в новую. Переносить
	 * молча десяток гигабайт по чужому диску - худшее, что тут можно сделать.
	 */
	private void save() {
		if (chosen == 0) {
			settings.remove("memory");
		} else {
			settings.setProperty("memory", String.valueOf(chosen));
		}
		if (dir.equals(Files2.home())) {
			settings.remove("dir");
		} else {
			settings.setProperty("dir", dir.toString());
			try {
				Files.createDirectories(dir);
			} catch (Exception broken) {
				// Не создалась - скажет сам запуск, там ошибка видна игроку.
			}
		}
		onSave.run();
		frame.dispose();
	}
}
