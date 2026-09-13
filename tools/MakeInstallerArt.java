import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Рисует картинки для мастера установки: WiX ждёт их ровно такого размера и в BMP.
 *
 * Запускать из корня репозитория, это делает tools/pack.ps1:
 *     java tools/MakeInstallerArt.java build/wixres
 *
 * Свои надписи мастер печатает поверх чёрным, поэтому под текст оставляем светлое поле, а нашу
 * тёмную картинку держим сбоку. Иначе текст на ней не читается.
 */
public final class MakeInstallerArt {

	private static final Color LIGHT = new Color(0xF5F6F8);
	private static final Color GOLD = new Color(0xC98A12);
	private static final Color INK = new Color(0x1B2027);
	private static final String ART = "src/main/resources/ru/mcgl/launcher/head.png";
	private static final String ICON = "src/main/resources/ru/mcgl/launcher/icon.png";

	public static void main(String[] args) throws Exception {
		String out = args.length > 0 ? args[0] : "build/wixres";
		new File(out).mkdirs();
		BufferedImage art = ImageIO.read(new File(ART));
		BufferedImage icon = ImageIO.read(new File(ICON));
		banner(icon, out);
		dialog(art, out);
		System.out.println("Картинки мастера: " + out);
	}

	/** Полоса сверху на страницах мастера: слева его текст, справа наш значок. */
	private static void banner(BufferedImage icon, String out) throws Exception {
		BufferedImage image = new BufferedImage(493, 58, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = nice(image);
		g.setColor(LIGHT);
		g.fillRect(0, 0, 493, 58);
		g.drawImage(icon, 493 - 52, 7, 44, 44, null);
		g.setColor(GOLD);
		g.fillRect(0, 56, 493, 2);
		g.dispose();
		ImageIO.write(image, "bmp", new File(out + "/banner.bmp"));
	}

	/**
	 * Первая и последняя страницы. Слева наша картинка, справа светлое поле: свой текст мастер
	 * печатает начиная примерно со 140-го пикселя, поэтому полосу держим уже.
	 */
	private static void dialog(BufferedImage art, String out) throws Exception {
		BufferedImage image = new BufferedImage(493, 312, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = nice(image);
		g.setColor(LIGHT);
		g.fillRect(0, 0, 493, 312);

		int side = 164;
		double scale = 312.0 / art.getHeight();
		int drawW = (int) Math.ceil(art.getWidth() * scale);
		// Полоса узкая, а маскот сидит правее середины: двигаем кадр к нему, иначе в полосу
		// попадает тёмная стена и картинка выглядит чёрным прямоугольником.
		int focus = (int) (drawW * 0.70) - side / 2;
		g.setClip(0, 0, side, 312);
		g.drawImage(art, -focus, 0, drawW, 312, null);
		g.setClip(null);

		g.setColor(GOLD);
		g.fillRect(side, 0, 2, 312);

		g.setColor(INK);
		g.setFont(new Font("Segoe UI", Font.BOLD, 20));
		g.drawString("ПОРА КОПАТЬ", side + 22, 250);
		g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
		g.setColor(GOLD);
		g.drawString("porakopatb.com", side + 23, 270);

		g.dispose();
		ImageIO.write(image, "bmp", new File(out + "/dialog.bmp"));
	}

	private static Graphics2D nice(BufferedImage image) {
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		return g;
	}
}
