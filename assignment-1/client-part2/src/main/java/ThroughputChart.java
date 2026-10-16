import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

// draws a line chart of throughput (swipes/s) for each 10 second bucket and saves it as a png
public class ThroughputChart {

  public static void draw(double[] rates, String fileName) throws IOException {
    int width = 900;
    int height = 500;
    int left = 80;
    int right = 40;
    int top = 60;
    int bottom = 60;
    int plotWidth = width - left - right;
    int plotHeight = height - top - bottom;

    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, width, height);
    g.setFont(new Font("SansSerif", Font.PLAIN, 13));

    // top of the y axis, rounded up to the next 1000
    double max = 1;
    for (double rate : rates) {
      max = Math.max(max, rate);
    }
    double yMax = Math.ceil(max / 1000) * 1000;

    // grid lines and y axis numbers
    for (int i = 0; i <= 5; i++) {
      int y = top + plotHeight - i * plotHeight / 5;
      g.setColor(Color.LIGHT_GRAY);
      g.drawLine(left, y, left + plotWidth, y);
      g.setColor(Color.BLACK);
      g.drawString(String.valueOf((int) (yMax * i / 5)), 15, y + 5);
    }

    // axes
    g.setColor(Color.BLACK);
    g.drawLine(left, top, left, top + plotHeight);
    g.drawLine(left, top + plotHeight, left + plotWidth, top + plotHeight);

    // the line, one point per bucket
    g.setStroke(new BasicStroke(2));
    int prevX = -1;
    int prevY = -1;
    for (int i = 0; i < rates.length; i++) {
      int x = left + (rates.length == 1 ? plotWidth / 2 : i * plotWidth / (rates.length - 1));
      int y = top + plotHeight - (int) (rates[i] / yMax * plotHeight);
      g.setColor(Color.BLUE);
      if (prevX >= 0) {
        g.drawLine(prevX, prevY, x, y);
      }
      g.fillOval(x - 4, y - 4, 8, 8);
      g.setColor(Color.BLACK);
      g.drawString((i * 10) + "s", x - 10, top + plotHeight + 20);
      prevX = x;
      prevY = y;
    }

    // titles
    g.setFont(new Font("SansSerif", Font.BOLD, 16));
    g.drawString("Ingest throughput over time (10 second buckets)", left, 35);
    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
    g.drawString("swipes/s", 15, top - 12);
    g.drawString("time since start of the run", left + plotWidth / 2 - 80, height - 15);

    g.dispose();
    ImageIO.write(image, "png", new File(fileName));
  }
}
