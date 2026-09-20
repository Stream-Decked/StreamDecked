package dev.wolfieboy09.streamdecked.core;

import org.jetbrains.annotations.Nullable;

/**
 * Every Stream Deck variant we know how to talk to, with the hardware quirks that matter
 * for the HID protocol.
 *
 * <p>Protocol facts here are derived from the reverse-engineering work in
 * python-elgato-streamdeck / elgato-streamdeck (rust). If Elgato ships new hardware, adding
 * a constant here is normally all that is needed.</p>
 */
@SuppressWarnings("unused")
public enum DeckModel {
    // ---- Gen 1: BMP images, 16 byte image headers, feature reports 0x05/0x0B ----
    // Mini family: panels mounted 90deg off-axis; counter-clockwise (R270) with no
    // mirroring renders right-way-up on real hardware (matches python-elgato-streamdeck).
    // CW90 left the image upside-down. Do not add a mirror flag on top of the rotation.
    ORIGINAL       ("Stream Deck",              0x0060, 5, 3, Gen.V1, ImageSpec.bmp (72,  72, Rot.R0,   true,  true ), null, 0, 0, true ),
    MINI           ("Stream Deck Mini",         0x0063, 3, 2, Gen.V1, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0, false),
    MINI_MK2       ("Stream Deck Mini MK.2",    0x0090, 3, 2, Gen.V1, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0, false),
    MINI_DISCORD   ("Stream Deck Mini Discord", 0x00b3, 3, 2, Gen.V1, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0, false),
    MINI_MK2_MODULE("Stream Deck Mini Module",  0x00b8, 3, 2, Gen.V1, ImageSpec.bmp (80,  80, Rot.R270, false, false), null, 0, 0, false),

    // ---- Gen 2: JPEG images, 8 byte image headers, feature report 0x03 ----
    ORIGINAL_V2    ("Stream Deck V2",           0x006d, 5, 3, Gen.V2, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0, false),
    MK2            ("Stream Deck MK.2",         0x0080, 5, 3, Gen.V2, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0, false),
    MK2_SCISSOR    ("Stream Deck MK.2 Scissor", 0x00a5, 5, 3, Gen.V2, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0, false),
    MK2_MODULE     ("Stream Deck MK.2 Module",  0x00b9, 5, 3, Gen.V2, ImageSpec.jpeg(72,  72, Rot.R0,   true,  true ), null, 0, 0, false),
    XL             ("Stream Deck XL",           0x006c, 8, 4, Gen.V2, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ), null, 0, 0, false),
    XL_V2          ("Stream Deck XL V2",        0x008f, 8, 4, Gen.V2, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ), null, 0, 0, false),
    XL_V2_MODULE   ("Stream Deck XL Module",    0x00ba, 8, 4, Gen.V2, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ), null, 0, 0, false),

    /** 8 keys, 4 encoders and a 800x100 touch strip. */
    PLUS           ("Stream Deck +",            0x0084, 4, 2, Gen.V2, ImageSpec.jpeg(120, 120, Rot.R0,  false, false),
            ImageSpec.jpeg(800, 100, Rot.R0, false, false), 4, 0, false),
    PLUS_XL        ("Stream Deck + XL",         0x00c6, 9, 4, Gen.V2, ImageSpec.jpeg(120, 120, Rot.R270, false, false),
            ImageSpec.jpeg(100, 1200, Rot.R270, false, false), 6, 0, false),
    /** 8 keys, 2 capacitive touch points and a 248x58 info screen. */
    NEO            ("Stream Deck Neo",          0x009a, 4, 2, Gen.V2, ImageSpec.jpeg(96,  96, Rot.R0,   true,  true ),
            ImageSpec.jpeg(248, 58, Rot.R180, false, false), 0, 2, false),

    /** No screens at all. */
    PEDAL          ("Stream Deck Pedal",        0x0086, 3, 1, Gen.V2, null, null, 0, 0, false);

    public static final int ELGATO_VENDOR_ID = 0x0fd9;

    /** Image transport generation. Decides header layout, feature report ids and input offsets. */
    public enum Gen { V1, V2 }

    /** Clockwise rotation applied to an image before it is sent to the device. */
    public enum Rot { R0, R90, R180, R270 }

    public enum ImageMode { BMP, JPEG }

    /**
     * How a panel expects its pixels: size, container format and the mirror/rotate dance the
     * firmware requires (the panels are physically mounted in various orientations).
     */
    public record ImageSpec(ImageMode mode, int width, int height, Rot rotation,
                            boolean mirrorX, boolean mirrorY) {
        static ImageSpec bmp(int w, int h, Rot r, boolean mx, boolean my) {
            return new ImageSpec(ImageMode.BMP, w, h, r, mx, my);
        }
        static ImageSpec jpeg(int w, int h, Rot r, boolean mx, boolean my) {
            return new ImageSpec(ImageMode.JPEG, w, h, r, mx, my);
        }
    }

    private final String displayName;
    private final int productId;
    private final int columns;
    private final int rows;
    private final Gen generation;
    private final ImageSpec keyImage;
    private final ImageSpec screenImage;
    private final int encoderCount;
    private final int touchpointCount;
    private final boolean mirroredKeyIndices;

    DeckModel(String displayName, int productId, int columns, int rows, Gen generation,
              ImageSpec keyImage, ImageSpec screenImage, int encoderCount, int touchpointCount,
              boolean mirroredKeyIndices) {
        this.displayName = displayName;
        this.productId = productId;
        this.columns = columns;
        this.rows = rows;
        this.generation = generation;
        this.keyImage = keyImage;
        this.screenImage = screenImage;
        this.encoderCount = encoderCount;
        this.touchpointCount = touchpointCount;
        this.mirroredKeyIndices = mirroredKeyIndices;
    }

    /** Resolves the vendor/product ids to a known model, or null for an unknown Elgato device. */
    public static @Nullable DeckModel fromUsbIds(int vendorId, int productId) {
        if (vendorId != ELGATO_VENDOR_ID) return null;
        for (DeckModel m : values()) {
            if (m.productId == productId) return m;
        }
        return null;
    }

    public String displayName()    { return displayName; }
    public int productId()         { return productId; }
    public int vendorId()          { return ELGATO_VENDOR_ID; }
    public int columns()           { return columns; }
    public int rows()              { return rows; }
    public int keyCount()          { return columns * rows; }
    public Gen generation()        { return generation; }
    public int encoderCount()      { return encoderCount; }
    public int touchpointCount()   { return touchpointCount; }

    /** Pixel format the keys want, or null on hardware without key screens (Pedal). */
    public ImageSpec keyImage()    { return keyImage; }
    /** Pixel format of the LCD strip / info screen, or null if there is none. */
    public ImageSpec screenImage() { return screenImage; }

    public boolean hasKeyScreens() { return keyImage != null; }
    public boolean hasScreen()     { return screenImage != null; }
    /** Only the Plus family supports partial (region) writes to the LCD strip. */
    public boolean hasPartialScreenWrites() { return this == PLUS || this == PLUS_XL; }

    /** The original 2017 panel reports and addresses keys right-to-left within each row. */
    public boolean mirroredKeyIndices() { return mirroredKeyIndices; }

    /** Number of button states in an input report (physical keys + capacitive touch points). */
    public int buttonStateCount() { return keyCount() + touchpointCount; }

    public int keyIndex(int column, int row) { return row * columns + column; }
    public int columnOf(int keyIndex)        { return keyIndex % columns; }
    public int rowOf(int keyIndex)           { return keyIndex / columns; }

    /** Translates a logical key index into the wire index this device expects. */
    public int toWireKeyIndex(int keyIndex) {
        if (!mirroredKeyIndices) return keyIndex;
        int col = keyIndex % columns;
        return (keyIndex - col) + ((columns - 1) - col);
    }

    // ---- HID report geometry ----

    public int imageReportLength()       { return this == ORIGINAL ? 8191 : 1024; }
    public int imageReportHeaderLength() { return generation == Gen.V1 ? 16 : 8; }

    /**
     * Payload bytes per image report. The original panel is special: it always wants the
     * bitmap split into exactly two halves.
     */
    public int imageReportPayloadLength(int totalImageBytes) {
        if (this == ORIGINAL) return Math.max(1, totalImageBytes / 2);
        return imageReportLength() - imageReportHeaderLength();
    }

    @Override public String toString() { return displayName; }
}