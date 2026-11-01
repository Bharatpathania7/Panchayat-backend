
        package com.panchayat.backend.chat.service;

import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Service
public class ChatImageValidator {

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_WIDTH = 8000;
    private static final int MAX_HEIGHT = 8000;
    private static final long MAX_PIXELS = 20_000_000L;

    private static final Set<String> ALLOWED_FORMATS =
            Set.of("JPEG", "PNG");

    public String validateAndDetectContentType(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image cannot be empty.");
        }

        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "Image must not exceed 5 MB."
            );
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(imageBytes)
        )) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Invalid image file."
                );
            }

            Iterator<ImageReader> readers =
                    ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw new IllegalArgumentException(
                        "File is not a supported image."
                );
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(input, true, true);

                String format = reader.getFormatName()
                        .toUpperCase(Locale.ROOT);

                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new IllegalArgumentException(
                            "Only JPEG and PNG images are supported."
                    );
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                if (width <= 0 || height <= 0
                        || width > MAX_WIDTH
                        || height > MAX_HEIGHT
                        || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException(
                            "Image dimensions exceed the allowed limits."
                    );
                }

                // Decode the image to reject corrupt image data.
                if (reader.read(0) == null) {
                    throw new IllegalArgumentException(
                            "Image data is invalid or corrupted."
                    );
                }

                return format.equals("JPEG")
                        ? "image/jpeg"
                        : "image/png";

            } finally {
                reader.dispose();
            }

        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "Unable to validate image data.", ex
            );
        }
    }
}
