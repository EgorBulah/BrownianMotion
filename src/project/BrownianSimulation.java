import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class BrownianSimulation extends JFrame {
    private static final int N = 50;
    private static final int M = 50;
    private static final int K = 1000;
    private static final int TOTAL_STEPS = 200;
    private static final int SNAPSHOT_INTERVAL = 40;
    private static final long SEED = 42;

    private static final boolean USE_SYNCHRONIZATION = true;

    private final AtomicInteger[][] safeGrid = new AtomicInteger[N][M];
    private final int[][] unsafeGrid = new int[N][M];
    private final VisualPanel panel;

    private final CyclicBarrier barrier = new CyclicBarrier(K + 1);
    private int snapshotCount = 0;

    public BrownianSimulation() {
        setTitle("Броунівський рух");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        for (int i = 0; i < N; i++) {
            for (int j = 0; j < M; j++) {
                safeGrid[i][j] = new AtomicInteger(0);
                unsafeGrid[i][j] = 0;
            }
        }

        panel = new VisualPanel();
        add(new JScrollPane(panel), BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        new Thread(this::executeSimulation).start();
    }

    private void executeSimulation() {
        AtomThread[] threads = new AtomThread[K];
        for (int i = 0; i < K; i++) {
            threads[i] = new AtomThread(i, barrier);
            threads[i].start();
        }

        try {
            // Фіксація початкового стану
            barrier.await();
            SwingUtilities.invokeAndWait(() -> {
                panel.paintImmediately(0, 0, panel.getWidth(), panel.getHeight());
                saveSnapshot(0);
            });

            for (int step = 1; step <= TOTAL_STEPS; step++) {
                barrier.await(); // Сигнал до руху
                barrier.await(); // Очікування завершення руху

                if (step % SNAPSHOT_INTERVAL == 0) {
                    final int currentStep = step;
                    SwingUtilities.invokeAndWait(() -> {
                        panel.paintImmediately(0, 0, panel.getWidth(), panel.getHeight());
                        saveSnapshot(currentStep);
                    });
                }
                Thread.sleep(10);
                panel.repaint();
            }
        } catch (Exception e) { e.printStackTrace(); }

        printFinalReport();
    }

    private void saveSnapshot(int step) {
        snapshotCount++;
        int foundAtoms = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < M; j++) {
                foundAtoms += USE_SYNCHRONIZATION ? safeGrid[i][j].get() : unsafeGrid[i][j];
            }
        }

        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        panel.paintAll(g2d);
        g2d.dispose();

        try {
            ImageIO.write(image, "png", new File("snapshot_step_" + step + ".png"));
            System.out.println("[Знімок " + snapshotCount + "] Крок " + step + ": знайдено " + foundAtoms + " часток.");
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void printFinalReport() {
        int finalCount = 0;
        for (int i = 0; i < N; i++)
            for (int j = 0; j < M; j++)
                finalCount += USE_SYNCHRONIZATION ? safeGrid[i][j].get() : unsafeGrid[i][j];

        System.out.println("\nФІНАЛЬНИЙ РЕЗУЛЬТАТ");
        System.out.println("Початково: " + K + " | Виявлено: " + finalCount);
        System.out.println("Втрати (Race Condition): " + (K - finalCount));
        System.out.println("Зроблено знімків: " + snapshotCount);
    }

    class AtomThread extends Thread {
        private int x, y;
        private final Random random;
        private final CyclicBarrier barrier;

        public AtomThread(int id, CyclicBarrier barrier) {
            this.random = new Random(SEED + id);
            this.barrier = barrier;
            this.x = N / 2; this.y = M / 2;
            if (USE_SYNCHRONIZATION) safeGrid[x][y].incrementAndGet();
            else unsafeGrid[x][y]++;
        }

        @Override
        public void run() {
            try {
                barrier.await();
                for (int step = 1; step <= TOTAL_STEPS; step++) {
                    barrier.await();
                    move();
                    barrier.await();
                }
            } catch (Exception ignored) {}
        }

        private void move() {
            int dir = random.nextInt(4);
            int nx = x, ny = y;
            if (dir == 0) nx++; else if (dir == 1) nx--; else if (dir == 2) ny++; else ny--;
            if (nx >= 0 && nx < N && ny >= 0 && ny < M) {
                if (USE_SYNCHRONIZATION) {
                    safeGrid[x][y].decrementAndGet();
                    x = nx; y = ny;
                    safeGrid[x][y].incrementAndGet();
                } else {
                    unsafeGrid[x][y]--; x = nx; y = ny; unsafeGrid[x][y]++;
                }
            }
        }
    }

    class VisualPanel extends JPanel {
        private static final int CELL_SIZE = 16;
        private final Font gridFont = new Font("SansSerif", Font.BOLD, 9);

        public VisualPanel() {
            setPreferredSize(new Dimension(M * CELL_SIZE + 1, N * CELL_SIZE + 1));
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

            for (int i = 0; i < N; i++) {
                for (int j = 0; j < M; j++) {
                    int val = USE_SYNCHRONIZATION ? safeGrid[i][j].get() : unsafeGrid[i][j];
                    int x = j * CELL_SIZE;
                    int y = i * CELL_SIZE;

                    if (val > 0) {
                        int intensity = Math.min(255, 20 + val * 40);
                        g2.setColor(new Color(255, 255 - intensity, 255 - intensity));
                        g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
                    }

                    g2.setColor(new Color(200, 200, 200));
                    g2.drawRect(x, y, CELL_SIZE, CELL_SIZE);

                    if (val > 0) {
                        g2.setColor(val > 5 ? Color.WHITE : Color.BLACK);
                        g2.setFont(gridFont);
                        String text = String.valueOf(val);
                        FontMetrics fm = g2.getFontMetrics();
                        int tx = x + (CELL_SIZE - fm.stringWidth(text)) / 2;
                        int ty = y + ((CELL_SIZE - fm.getHeight()) / 2) + fm.getAscent();
                        g2.drawString(text, tx, ty);
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(BrownianSimulation::new);
    }
}
