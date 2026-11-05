package com.daniel.presentation.view;

import com.daniel.presentation.view.util.UiTheme;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.transform.Transform;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4CV:RF-06 item 1: writes PNGs of the failure pane, by {@code snapshot}, at scales 1.0 and 1.5 and in the two states
 * (failure and trying). The pane is the root of a {@link Scene} with {@code /styles/app.css} and the font of the app,
 * without any {@code Stage}. The files go to {@code <java.io.tmpdir>/u4c-vis/} and overwrite the ones already there.
 * It only uses the public face of the pane (constructor and {@code showTrying()}), so it serves unchanged before and
 * after the style change. The images are written with {@link ImageIO} from the pixels ({@code javafx-swing} is not a
 * dependency of the project).
 */
class OpeningFailurePaneCaptureTest {

    private static final String REASON = "arquivo de dados inacessível";
    private static final int FILE_LENGTH = 180;
    private static final int COPIES_LENGTH = 190;
    private static final int MAX_SEGMENT = 24;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";

    /**
     * A path whose {@code toAbsolutePath().toString()} has exactly {@code length} characters: the temporary folder
     * followed by folder names made only of letters, without spaces. Nothing is created on disk.
     */
    private static Path pathOfLength(int length, int seed) {
        Path path = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        int segment = 0;
        while (true) {
            String current = path.toString();
            int separator = current.endsWith(File.separator) ? 0 : 1;
            int remaining = length - current.length() - separator;
            assertTrue(remaining >= 1, "the temporary folder is too long for a path of " + length + " characters: " + current);
            int letters = Math.min(MAX_SEGMENT, remaining);
            if (remaining > MAX_SEGMENT && remaining - MAX_SEGMENT < 2) letters = MAX_SEGMENT - 2; // never leave a segment of 0 letters
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < letters; i++) name.append(LETTERS.charAt((seed + segment * 7 + i * 3) % LETTERS.length()));
            path = path.resolve(name.toString());
            segment++;
            if (path.toAbsolutePath().toString().length() == length) break;
        }
        Path result = path.toAbsolutePath();
        assertEquals(length, result.toString().length());
        return result;
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) buffered.setRGB(x, y, reader.getArgb(x, y));
        }
        return buffered;
    }

    private static void capture(OpeningFailurePane pane, double scale, Path target) throws IOException {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(scale, scale));
        WritableImage image = pane.snapshot(parameters, null);
        assertNotNull(image, target.getFileName().toString());
        double expectedWidth = OpeningFailurePane.WIDTH * scale;
        assertEquals(expectedWidth, image.getWidth(), 1.0, "width of " + target.getFileName());
        assertTrue(image.getHeight() > 0, "height of " + target.getFileName());

        boolean written = ImageIO.write(toBufferedImage(image), "png", target.toFile());
        assertTrue(written, "no PNG writer for " + target);
        assertTrue(Files.isRegularFile(target) && Files.size(target) > 0, "not written: " + target);
        BufferedImage back = ImageIO.read(target.toFile());
        assertNotNull(back, "unreadable: " + target);
        assertEquals(expectedWidth, back.getWidth(), 1.0, "width written in " + target.getFileName());
    }

    @Test
    void writesTheFourCapturesOfThePane() {
        FxTestSupport.runAndWait(() -> {
            Path folder = Path.of(System.getProperty("java.io.tmpdir"), "u4c-vis");
            Files.createDirectories(folder);
            Path file = pathOfLength(FILE_LENGTH, 3);
            Path copies = pathOfLength(COPIES_LENGTH, 11);

            OpeningFailurePane pane = new OpeningFailurePane(REASON, file, copies);
            Scene scene = new Scene(pane, OpeningFailurePane.WIDTH, Region.USE_COMPUTED_SIZE);
            var css = OpeningFailurePaneCaptureTest.class.getResource("/styles/app.css");
            assertNotNull(css, "/styles/app.css");
            scene.getStylesheets().add(css.toExternalForm());
            UiTheme.applyFont(scene.getRoot());

            settle(pane);
            capture(pane, 1.0, folder.resolve("falha-100.png"));
            capture(pane, 1.5, folder.resolve("falha-150.png"));

            pane.showTrying();
            settle(pane);
            capture(pane, 1.0, folder.resolve("tentando-100.png"));
            capture(pane, 1.5, folder.resolve("tentando-150.png"));
        });
    }

    /** Applies the CSS and lays the pane out at its width, at the height it prefers (no window does it here). */
    private static void settle(OpeningFailurePane pane) {
        for (int pass = 0; pass < 3; pass++) {
            pane.applyCss();
            pane.resize(OpeningFailurePane.WIDTH, pane.prefHeight(OpeningFailurePane.WIDTH));
            pane.layout();
        }
    }
}
