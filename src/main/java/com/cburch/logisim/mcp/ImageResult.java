/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Tool result carrying an image (sent as MCP image content) plus optional extra data that is
 * serialized to JSON text next to it.
 */
public record ImageResult(String base64Data, String mimeType, Object details) {

  /** Encodes {@code image} as PNG. */
  public static ImageResult png(BufferedImage image, Object details) {
    try {
      final var out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return new ImageResult(Base64.getEncoder().encodeToString(out.toByteArray()), "image/png", details);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
