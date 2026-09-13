package ru.mcgl.launcher;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/** Цвета, шрифты и самодельные детали окна: Swing своими средствами рисует прошлый век. */
final class Skin {

	static final Color BG = new Color(0x0B0D11);
	static final Color FIELD = new Color(0x10151C);
	static final Color LINE = new Color(0x28313F);
	static final Color TEXT = new Color(0xE2E6EE);
	static final Color MUTED = new Color(0x8B95A8);
	static final Color GOLD = new Color(0xF0B53F);
	static final Color GOLD_TOP = new Color(0xF8C860);
	static final Color INK = new Color(0x2A1C02);
	static final Color OFF = new Color(0x1B212B);
	static final Color OFF_TEXT = new Color(0x646E7E);

	private static final String FACE = "Segoe UI";

	private Skin() {
	}

	static Font font(int style, float size) {
		Font found = new Font(FACE, style, Math.round(size));
		// Если Segoe UI нет, Java молча подставит логический шрифт - это нормально.
		return found.deriveFont(style, size);
	}

	static JLabel label(String text, Color colour, int style, float size) {
		JLabel out = new JLabel(text);
		out.setForeground(colour);
		out.setFont(font(style, size));
		return out;
	}

	/** Шапка: картинка во всю ширину, снизу растворяется в фоне, поверх название. */
	static final class Header extends JComponent {
		private final Image picture;

		Header(Image picture, int width, int height) {
			this.picture = picture;
			setPreferredSize(new Dimension(width, height));
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(BG);
			g2.fillRect(0, 0, w, h);
			if (picture != null) {
				// Во всю ширину и целиком: если тянуть по высоте, у маскота срезается макушка.
				int drawH = (int) Math.ceil(w * (320.0 / 920.0));
				g2.drawImage(picture, 0, 0, w, drawH, null);
			}
			// Нижняя треть уходит в фон, иначе между шапкой и формой остаётся резкий шов.
			g2.setPaint(new GradientPaint(0, h * 0.45f, new Color(11, 13, 17, 0), 0, h, BG));
			g2.fillRect(0, (int) (h * 0.45f), w, h);

			g2.setFont(font(Font.BOLD, 27f));
			g2.setColor(new Color(0, 0, 0, 170));
			g2.drawString("ПОРА КОПАТЬ", 29, h - 47);
			g2.setColor(GOLD);
			g2.drawString("ПОРА КОПАТЬ", 28, h - 48);
			g2.setFont(font(Font.PLAIN, 12.5f));
			g2.setColor(TEXT);
			g2.drawString("Гриф-сервер Minecraft 1.21.1", 29, h - 26);
			g2.dispose();
		}
	}

	/** Поле ввода со скруглением и золотой рамкой, когда в нём курсор. */
	static void dress(JTextComponent field) {
		field.setOpaque(false);
		field.setBackground(FIELD);
		field.setForeground(TEXT);
		field.setCaretColor(GOLD);
		field.setFont(font(Font.PLAIN, 14f));
		field.setBorder(new EmptyBorder(11, 13, 11, 13));
		field.setPreferredSize(new Dimension(0, 40));
	}

	/** Обёртка, которая и рисует фон поля: сам JTextField скруглять не умеет. */
	static final class Field extends JComponent {
		private final JTextComponent inner;
		private boolean focused;

		Field(JTextComponent inner) {
			this.inner = inner;
			dress(inner);
			setLayout(new java.awt.BorderLayout());
			add(inner);
			setPreferredSize(new Dimension(0, 40));
			inner.addFocusListener(new java.awt.event.FocusAdapter() {
				@Override
				public void focusGained(java.awt.event.FocusEvent e) {
					focused = true;
					repaint();
				}

				@Override
				public void focusLost(java.awt.event.FocusEvent e) {
					focused = false;
					repaint();
				}
			});
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(FIELD);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
			g2.setColor(focused ? GOLD : LINE);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
			g2.dispose();
		}
	}

	/** Золотая кнопка: Swing рисует свои по правилам системы и наш цвет игнорирует. */
	static final class GoldButton extends JComponent {
		private final String text;
		private final Runnable action;
		private boolean hover;
		private boolean pressed;
		private boolean enabledNow = true;

		GoldButton(String text, Runnable action) {
			this.text = text;
			this.action = action;
			setPreferredSize(new Dimension(0, 44));
			setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mouseEntered(MouseEvent e) {
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hover = false;
					pressed = false;
					repaint();
				}

				@Override
				public void mousePressed(MouseEvent e) {
					pressed = true;
					repaint();
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					boolean fire = pressed && enabledNow;
					pressed = false;
					repaint();
					if (fire) {
						action.run();
					}
				}
			});
		}

		void setOn(boolean on) {
			enabledNow = on;
			setCursor(java.awt.Cursor.getPredefinedCursor(
					on ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			int shift = pressed ? 1 : 0;
			// Выключенная кнопка серая, а не полупрозрачно-золотая: иначе она читается как
			// золотая, но грязная, и человек жмёт по ней впустую.
			if (enabledNow) {
				g2.setPaint(new GradientPaint(0, 0, hover ? Color.WHITE : GOLD_TOP, 0, getHeight(), GOLD));
			} else {
				g2.setPaint(OFF);
			}
			g2.fillRoundRect(0, shift, getWidth(), getHeight() - 1, 12, 12);
			g2.setFont(font(Font.BOLD, 15f));
			g2.setColor(enabledNow ? INK : OFF_TEXT);
			int textWidth = g2.getFontMetrics().stringWidth(text);
			g2.drawString(text, (getWidth() - textWidth) / 2,
					getHeight() / 2 + g2.getFontMetrics().getAscent() / 2 - 2 + shift);
			g2.dispose();
		}
	}

	/** Тонкая полоса загрузки: своя, потому что системная выглядит чужеродно. */
	static final class Bar extends JComponent {
		private double done;
		private boolean spinning;
		private long started = System.currentTimeMillis();

		Bar() {
			setPreferredSize(new Dimension(0, 6));
			setVisible(false);
		}

		void set(double value) {
			spinning = false;
			done = Math.max(0, Math.min(1, value));
			repaint();
		}

		void spin() {
			spinning = true;
			started = System.currentTimeMillis();
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(LINE);
			g2.fillRoundRect(0, 0, w, h, h, h);
			g2.setColor(GOLD);
			if (spinning) {
				int run = (int) ((System.currentTimeMillis() - started) / 4 % (w + 120)) - 120;
				g2.fillRoundRect(Math.max(0, run), 0, Math.min(120, w - Math.max(0, run)), h, h, h);
			} else {
				g2.fillRoundRect(0, 0, (int) (w * done), h, h, h);
			}
			g2.dispose();
		}
	}

	/** Подпись над полем: мелкая, разрядкой, приглушённая. */
	static JLabel caption(String text) {
		return label(text.toUpperCase(java.util.Locale.ROOT), MUTED, Font.PLAIN, 10.5f);
	}

	/** Чтобы кнопка оживала и гасла вместе с полями. */
	static void onType(JTextComponent field, Runnable what) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				what.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				what.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				what.run();
			}
		});
	}

	static JTextField text() {
		return new JTextField();
	}

	static JPasswordField secret() {
		return new JPasswordField();
	}
}
