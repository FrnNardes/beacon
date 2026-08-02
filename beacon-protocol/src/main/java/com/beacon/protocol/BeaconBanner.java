package com.beacon.protocol;

/**
 * ASCII art banner and shared constants for the Beacon visual identity.
 * Used by both server and client on startup.
 */
public final class BeaconBanner {

    private BeaconBanner() {}

    /**
     * 3D block-style BEACON banner using Unicode box-drawing characters.
     * Each line is an element of the array for easy iteration.
     */
    public static final String[] BANNER_LINES = {
            "",
            "  ██████╗ ███████╗ █████╗  ██████╗ ██████╗ ███╗   ██╗",
            "  ██╔══██╗██╔════╝██╔══██╗██╔════╝██╔═══██╗████╗  ██║",
            "  ██████╔╝█████╗  ███████║██║     ██║   ██║██╔██╗ ██║",
            "  ██╔══██╗██╔══╝  ██╔══██║██║     ██║   ██║██║╚██╗██║",
            "  ██████╔╝███████╗██║  ██║╚██████╗╚██████╔╝██║ ╚████║",
            "  ╚═════╝ ╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═╝  ╚═══╝",
            ""
    };

    /**
     * ANSI color codes for the gradient effect (top to bottom).
     * Goes from bright cyan → blue → magenta for a beacon "beam" feel.
     */
    public static final String[] GRADIENT_COLORS = {
            "\u001B[38;5;87m",  // bright cyan
            "\u001B[38;5;81m",  // cyan
            "\u001B[38;5;75m",  // sky blue
            "\u001B[38;5;69m",  // blue
            "\u001B[38;5;63m",  // blue-purple
            "\u001B[38;5;135m", // purple
            "\u001B[38;5;171m", // magenta
            "\u001B[38;5;213m", // pink
    };

    public static final String RESET = "\u001B[0m";
    public static final String DIM = "\u001B[2m";

    /**
     * Prints the banner with gradient coloring to System.out.
     * @param subtitle text to show below the banner (e.g., "Server v1.0" or "Client")
     */
    public static void print(String subtitle) {
        // Clear screen and move cursor to top left
        System.out.print("\033[H\033[2J");
        System.out.flush();
        
        System.out.println();
        for (int i = 0; i < BANNER_LINES.length; i++) {
            String color = GRADIENT_COLORS[i % GRADIENT_COLORS.length];
            System.out.println(color + BANNER_LINES[i] + RESET);
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            // Center the subtitle roughly under the banner
            int bannerWidth = 54;
            int padding = Math.max(0, (bannerWidth - subtitle.length()) / 2);
            System.out.println(DIM + " ".repeat(padding + 2) + subtitle + RESET);
        }
        System.out.println();
    }
}
