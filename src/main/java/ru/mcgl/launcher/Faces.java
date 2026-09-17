package ru.mcgl.launcher;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;

/**
 * Лица игроков в строке «Сейчас в игре» (владелец, 17.09.2026): голова со скина на сайте,
 * 8×8 плюс слой шапки, увеличенная вдвое. Грузится в фоне, чтобы окно не ждало сайт; пока
 * не пришло - стандартное лицо. Один ник - одна загрузка на всё время работы окна.
 */
final class Faces {

	static final int SIZE = 16;
	private static final Map<String, ImageIcon> CACHE = new ConcurrentHashMap<>();
	private static ImageIcon fallback;

	private Faces() {
	}

	/** Лицо из кэша или стандартное; настоящее придёт в {@code then}, когда докачается. */
	static ImageIcon of(String nick, Consumer<ImageIcon> then) {
		ImageIcon ready = CACHE.get(nick);
		if (ready != null) {
			return ready;
		}
		Thread loader = new Thread(() -> {
			ImageIcon icon = load(nick);
			if (icon != null) {
				CACHE.put(nick, icon);
				SwingUtilities.invokeLater(() -> then.accept(icon));
			}
		}, "face-" + nick);
		loader.setDaemon(true);
		loader.start();
		return fallback();
	}

	private static ImageIcon load(String nick) {
		try {
			HttpURLConnection link = (HttpURLConnection) URI.create(Site.skinUrl(nick)).toURL().openConnection();
			link.setConnectTimeout(5000);
			link.setReadTimeout(8000);
			if (link.getResponseCode() != 200) {
				return null;
			}
			BufferedImage skin = ImageIO.read(link.getInputStream());
			return skin == null ? null : new ImageIcon(face(skin));
		} catch (Exception quiet) {
			return null;
		}
	}

	/** Голова 8×8 с (8,8) и шапка с (40,8) поверх, увеличенные без сглаживания. */
	static Image face(BufferedImage skin) {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, SIZE, SIZE, null);
		if (skin.getWidth() >= 48) {
			g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, SIZE, SIZE, null);
		}
		g.dispose();
		return out;
	}

	/** Стандартное лицо: тёмный квадрат с двумя точками глаз, пока настоящее не пришло. */
	private static synchronized ImageIcon fallback() {
		if (fallback == null) {
			BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out.createGraphics();
			g.setColor(new java.awt.Color(0x8B6A4B));
			g.fillRect(0, 0, SIZE, SIZE);
			g.setColor(new java.awt.Color(0x3E2A1A));
			g.fillRect(0, 0, SIZE, 4);
			g.setColor(java.awt.Color.WHITE);
			g.fillRect(4, 8, 2, 2);
			g.fillRect(10, 8, 2, 2);
			g.dispose();
			fallback = new ImageIcon(out);
		}
		return fallback;
	}
}
