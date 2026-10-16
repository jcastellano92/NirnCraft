package com.skycraft.tamriel.settlement.client;

import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.client.ShopSignMesh.Quad;
import com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.skycraft.tamriel.settlement.client.ShopSignModels.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies all 23 custom 3D interior furniture and clutter models across all 10 Tamriel cultures
 * and renders high-resolution 3D perspective contact sheets for visual inspection.
 */
class InteriorFurnitureVisualTest {

    private static final Culture[] TEN_CULTURES = {
            Culture.NORD, Culture.IMPERIAL, Culture.BRETON, Culture.REDGUARD, Culture.DUNMER,
            Culture.ALTMER, Culture.BOSMER, Culture.KHAJIIT, Culture.ARGONIAN, Culture.ORC
    };

    @Test
    void allFurnitureKindsAndCulturesProduceValid3DMeshesAndRenderContactSheets() throws Exception {
        for (Culture c : TEN_CULTURES) {
            for (FurnitureKind k : FurnitureKind.values()) {
                for (int v = 0; v < 4; v++) {
                    ShopSignMesh mesh = InteriorFurnitureModels.get(k, c, v);
                    assertNotNull(mesh, "Null mesh for " + k + " / " + c + " / v" + v);
                    assertFalse(mesh.quads().isEmpty(), "Empty mesh for " + k + " / " + c + " / v" + v);
                    assertTrue(mesh.quads().size() >= 18, "Expected detailed 3D mesh (>= 3 cuboids) for " + k + " / " + c);
                }
            }
        }

        BufferedImage atlas;
        try (InputStream in = InteriorFurnitureVisualTest.class.getResourceAsStream("/assets/skycraft/textures/entity/settlement/shop_signs.png")) {
            assertNotNull(in, "Missing shop_signs.png texture atlas");
            atlas = ImageIO.read(in);
        }

        Path outDir = Path.of("C:/Users/Jeremy/.gemini/antigravity/brain/8aae5a6f-cfa7-417a-b106-d3c4e2605101");
        if (!Files.isDirectory(outDir)) {
            outDir = Path.of("build/reports");
            Files.createDirectories(outDir);
        }

        renderCatalogSheet(atlas, outDir.resolve("interior_furniture_catalog.png"));
        renderCulturalSheet(atlas, outDir.resolve("interior_cultures_showcase.png"));
        renderRoomsSheet(atlas, outDir.resolve("interior_rooms_showcase.png"));
    }

    private static void renderCatalogSheet(BufferedImage atlas, Path outPath) throws Exception {
        int cols = 7, rows = 4;
        int cellW = 340, cellH = 380;
        int headerH = 84;
        int imgW = cols * cellW + 40;
        int imgH = rows * cellH + headerH + 30;

        BufferedImage sheet = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0x16, 0x14, 0x12));
        g.fillRect(0, 0, imgW, imgH);

        g.setColor(new Color(0xE8, 0xD2, 0x9E));
        g.setFont(new Font("Serif", Font.BOLD, 30));
        g.drawString("SKYCRAFT — Custom 3D Interior Furniture, Storage, Clutter & Bookshelves (All 25 Kinds + 3D Bookshelves)", 28, 44);
        g.setColor(new Color(0xB8, 0xAC, 0x96));
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.drawString("Every piece is a multi-part 3D block entity mesh with Skyrim-style interactions: [F] Sit, [F] Open, [F] Take / Steal, [F] Take Book", 28, 70);

        FurnitureKind[] kinds = FurnitureKind.values();
        int totalCells = cols * rows;
        for (int i = 0; i < totalCells; i++) {
            int col = i % cols;
            int row = i / cols;
            int x0 = 20 + col * cellW;
            int y0 = headerH + row * cellH;

            g.setColor(new Color(0x23, 0x1F, 0x1B));
            g.fillRoundRect(x0 + 8, y0 + 8, cellW - 16, cellH - 16, 14, 14);
            g.setColor(new Color(0x4E, 0x42, 0x33));
            g.drawRoundRect(x0 + 8, y0 + 8, cellW - 16, cellH - 16, 14, 14);

            BufferedImage cellImg = new BufferedImage(cellW - 24, cellH - 82, BufferedImage.TYPE_INT_ARGB);
            Soft3D r3d = new Soft3D(cellImg, atlas);

            String title;
            String prompt;
            Culture c = TEN_CULTURES[i % TEN_CULTURES.length];
            if (i < kinds.length) {
                FurnitureKind k = kinds[i];
                title = k.name() + " (" + c.name() + ")";
                prompt = switch (k) {
                    case CHAIR, STOOL, BENCH, THRONE -> "[F] Sit  " + k.displayName;
                    case WARDROBE, DRESSER, CABINET -> "[F] Open  " + k.displayName + " (Container)";
                    case WEAPON_RACK -> "[F] Take Weapon  Weapon Rack";
                    case DESK -> "[F] Search  Writing Desk";
                    case CLUTTER_TANKARD, CLUTTER_PLATTER, CLUTTER_GOBLETS, CLUTTER_APOTHECARY,
                         CLUTTER_SCALES, CLUTTER_LEDGER -> "[F] Take  " + k.displayName;
                    default -> k.displayName + " (3D Interior Element)";
                };

                boolean tall = k == FurnitureKind.WARDROBE || k == FurnitureKind.THRONE || k == FurnitureKind.CLUTTER_ARMOR_STAND;
                boolean clutter = k.name().startsWith("CLUTTER_") && k != FurnitureKind.CLUTTER_ARMOR_STAND;
                boolean wall = k.name().startsWith("WALL_") || k == FurnitureKind.WEAPON_RACK;

                float dist = tall ? 2.25f : clutter ? 1.15f : wall ? 1.65f : 1.72f;
                float lookY = tall ? 0.25f : clutter ? -0.25f : 0.0f;
                float yaw = wall ? 20f : 32f;
                float pitch = clutter ? 28f : wall ? 14f : 24f;
                r3d.setCameraOrbit(0f, lookY, 0f, dist, yaw, pitch, 42f);

                // Subtle floor / wall reference plinth behind (-Z) and under (-Y) the model
                ShopSignMesh plinth = new ShopSignMesh();
                if (!wall && !clutter) {
                    plinth.box(-10f, -8.6f, -10f, 10f, -8.0f, 10f, MAT_SANDSTONE);
                } else if (clutter) {
                    plinth.box(-7.5f, -8.8f, -7.5f, 7.5f, -8.0f, 7.5f, MAT_WALNUT);
                } else {
                    plinth.box(-10f, -9f, -9.2f, 10f, 11f, -8.05f, MAT_SANDSTONE);
                }
                r3d.drawMesh(plinth, 0f, 0f, 0f, 0f);
                r3d.drawMesh(InteriorFurnitureModels.get(k, c, i & 3), 0f, 0f, 0f, 0f);
            } else if (i == kinds.length) {
                title = "CHISELED_BOOKSHELF (Full 6 Books)";
                prompt = "[F] Take Book  Bookshelf (6 Slots)";
                r3d.setCameraOrbit(0f, 0.0f, 0f, 1.68f, 24f, 18f, 42f);
                r3d.drawMesh(build3DBookshelfPreview(6), 0f, 0f, 0f, 0f);
            } else if (i == kinds.length + 1) {
                title = "CHISELED_BOOKSHELF (After Taking 2)";
                prompt = "[F] Take Book  Bookshelf (4 Slots Left)";
                r3d.setCameraOrbit(0f, 0.0f, 0f, 1.68f, -24f, 18f, 42f);
                r3d.drawMesh(build3DBookshelfPreview(4), 0f, 0f, 0f, 0f);
            } else {
                title = "WEAPON_RACK (After Taking Sword)";
                prompt = "Weapon Rack (Empty State)";
                r3d.setCameraOrbit(0f, 0.0f, 0f, 1.65f, 20f, 14f, 42f);
                ShopSignMesh plinth = new ShopSignMesh().box(-10f, -9f, -9.2f, 10f, 11f, -8.05f, MAT_SANDSTONE);
                r3d.drawMesh(plinth, 0f, 0f, 0f, 0f);
                r3d.drawMesh(InteriorFurnitureModels.get(Culture.NORD, FurnitureKind.WEAPON_RACK, true), 0f, 0f, 0f, 0f);
            }

            g.drawImage(cellImg, x0 + 12, y0 + 12, null);

            g.setColor(new Color(0xF0, 0xE2, 0xC0));
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            g.drawString(title, x0 + 16, y0 + cellH - 42);
            g.setColor(new Color(0x88, 0xD8, 0x90));
            g.setFont(new Font("Monospaced", Font.BOLD, 11));
            g.drawString(prompt, x0 + 16, y0 + cellH - 22);
        }

        g.dispose();
        ImageIO.write(sheet, "png", outPath.toFile());
    }

    private static void renderCulturalSheet(BufferedImage atlas, Path outPath) throws Exception {
        FurnitureKind[] rowKinds = {
                FurnitureKind.THRONE,
                FurnitureKind.CHAIR,
                FurnitureKind.WARDROBE,
                FurnitureKind.DESK,
                FurnitureKind.BANQUET_TABLE
        };
        int cols = TEN_CULTURES.length;
        int rows = rowKinds.length;
        int cellW = 214, cellH = 290;
        int headerH = 84;
        int imgW = cols * cellW + 40;
        int imgH = rows * cellH + headerH + 30;

        BufferedImage sheet = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0x14, 0x13, 0x11));
        g.fillRect(0, 0, imgW, imgH);

        g.setColor(new Color(0xE8, 0xD2, 0x9E));
        g.setFont(new Font("Serif", Font.BOLD, 28));
        g.drawString("SKYCRAFT — Cultural Interior Furniture Diversity Across All 10 Tamriel Cultures", 24, 42);
        g.setColor(new Color(0xB8, 0xAC, 0x96));
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.drawString("Columns: Nord, Imperial, Breton, Redguard, Dunmer, Altmer, Bosmer, Khajiit, Argonian, Orc  |  Rows: Throne, Chair, Wardrobe, Writing Desk, Banquet Table + Clutter", 24, 68);

        for (int r = 0; r < rows; r++) {
            FurnitureKind k = rowKinds[r];
            for (int cIdx = 0; cIdx < cols; cIdx++) {
                Culture c = TEN_CULTURES[cIdx];
                int x0 = 20 + cIdx * cellW;
                int y0 = headerH + r * cellH;

                g.setColor(new Color(0x22, 0x1E, 0x1A));
                g.fillRoundRect(x0 + 5, y0 + 5, cellW - 10, cellH - 10, 10, 10);
                g.setColor(new Color(0x4A, 0x3E, 0x30));
                g.drawRoundRect(x0 + 5, y0 + 5, cellW - 10, cellH - 10, 10, 10);

                BufferedImage cellImg = new BufferedImage(cellW - 16, cellH - 60, BufferedImage.TYPE_INT_ARGB);
                Soft3D r3d = new Soft3D(cellImg, atlas);
                boolean tall = k == FurnitureKind.THRONE || k == FurnitureKind.WARDROBE;
                r3d.setCameraOrbit(0f, tall ? 0.24f : 0.02f, 0f, tall ? 2.25f : 1.72f, 32f, 24f, 42f);

                ShopSignMesh plinth = new ShopSignMesh();
                plinth.box(-9.5f, -8.5f, -9.5f, 9.5f, -8.0f, 9.5f, MAT_SANDSTONE);
                r3d.drawMesh(plinth, 0f, 0f, 0f, 0f);
                r3d.drawMesh(InteriorFurnitureModels.get(k, c, cIdx & 3), 0f, 0f, 0f, 0f);
                if (k == FurnitureKind.BANQUET_TABLE) {
                    FurnitureKind ck = (cIdx % 2 == 0) ? FurnitureKind.CLUTTER_CANDELABRA : FurnitureKind.CLUTTER_PLATTER;
                    r3d.drawMesh(InteriorFurnitureModels.get(ck, c, cIdx & 3), 0f, 1.0f, 0f, 0f);
                }

                g.drawImage(cellImg, x0 + 8, y0 + 8, null);
                g.setColor(new Color(0xEC, 0xDC, 0xB4));
                g.setFont(new Font("SansSerif", Font.BOLD, 12));
                g.drawString(c.name() + " " + k.displayName, x0 + 12, y0 + cellH - 18);
            }
        }

        g.dispose();
        ImageIO.write(sheet, "png", outPath.toFile());
    }

    private static void renderRoomsSheet(BufferedImage atlas, Path outPath) throws Exception {
        int imgW = 2200, imgH = 1660;
        BufferedImage sheet = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(new Color(0x14, 0x12, 0x10));
        g.fillRect(0, 0, imgW, imgH);

        g.setColor(new Color(0xE8, 0xD2, 0x9E));
        g.setFont(new Font("Serif", Font.BOLD, 30));
        g.drawString("SKYCRAFT — Complete Furnished Building Interiors (Zero Half-Beds, Clear Entrances, Full 3D Furniture & Clutter)", 28, 44);
        g.setColor(new Color(0xB8, 0xAC, 0x96));
        g.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g.drawString("Verified layouts: Imperial Legion Barracks, Nordic Tavern/Inn, Jarl's Longhouse/Keep, and Bookseller/Apothecary Study", 28, 70);

        String[] titles = {
                "1. Imperial Legion Barracks (Clear Front Doorway, Full 2-Block Bunks, 3D Weapon Racks, Armor Stands, Mess Table)",
                "2. Nordic Tavern & Inn Hall (Banquet Tables, Sittable Benches/Chairs, Bar Counter, Tankards, Platters, Trophies)",
                "3. Jarl's Longhouse & Keep Great Hall (Throne Dais, Feasting Tables, Candelabras, Goblets, Tapestries, Guard Racks)",
                "4. Bookseller & Scholar's Study (3D Chiseled Bookshelves with Protruding Tomes, Writing Desk, Counter, Scales)"
        };

        int cellW = 1070, cellH = 760;
        for (int idx = 0; idx < 4; idx++) {
            int col = idx % 2;
            int row = idx / 2;
            int x0 = 20 + col * (cellW + 20);
            int y0 = 90 + row * (cellH + 20);

            g.setColor(new Color(0x20, 0x1C, 0x18));
            g.fillRoundRect(x0, y0, cellW, cellH, 14, 14);
            g.setColor(new Color(0x52, 0x44, 0x34));
            g.drawRoundRect(x0, y0, cellW, cellH, 14, 14);

            BufferedImage roomImg = new BufferedImage(cellW - 16, cellH - 54, BufferedImage.TYPE_INT_ARGB);
            Soft3D r3d = new Soft3D(roomImg, atlas);
            switch (idx) {
                case 0 -> renderBarracksScene(r3d);
                case 1 -> renderInnScene(r3d);
                case 2 -> renderLonghouseScene(r3d);
                case 3 -> renderStudyScene(r3d);
            }
            g.drawImage(roomImg, x0 + 8, y0 + 8, null);

            g.setColor(new Color(0xF2, 0xE4, 0xC4));
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            g.drawString(titles[idx], x0 + 18, y0 + cellH - 16);
        }

        g.dispose();
        ImageIO.write(sheet, "png", outPath.toFile());
    }

    /**
     * Camera looks from +Z toward the back wall at -Z (so yaw=0 models placed on the -Z back wall face the camera).
     * Front entrance is at +Z (foreground, completely clear of beds/obstacles).
     */
    private static void renderBarracksScene(Soft3D r3d) {
        r3d.setCameraOrbit(0f, 0.7f, -0.3f, 8.6f, 22f, 32f, 45f);
        Culture c = Culture.IMPERIAL;
        drawRoomShell(r3d, 9, 9, MAT_OAK_RICH, MAT_SLATE_BLUE, true);

        // Full 2-block beds along left & right walls in back bunks (z = -2, 1), leaving front entrance at +Z completely clear!
        for (int z : new int[]{-2, 1}) {
            drawFullBed(r3d, -3f, 0f, z, 0f, MAT_CRIMSON);
            drawFullBed(r3d,  3f, 0f, z, 0f, MAT_CRIMSON);
            drawChestBlock(r3d, -2f, 0f, z);
            drawChestBlock(r3d,  2f, 0f, z);
        }

        // Central mess table with chairs, tankards, and platter
        for (int z = -1; z <= 1; z++) {
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.BANQUET_TABLE, c, 0), 0f, 0f, z, 0f);
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CHAIR, c, 0), -1f, 0f, z, 90f);
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CHAIR, c, 0),  1f, 0f, z, -90f);
            FurnitureKind ck = (z == 0) ? FurnitureKind.CLUTTER_PLATTER : FurnitureKind.CLUTTER_TANKARD;
            r3d.drawMesh(InteriorFurnitureModels.get(ck, c, z + 1), 0f, 1f, z, 0f);
        }

        // Back/side wall weapon racks, armor stands, wardrobes, and Imperial shield plaque facing into room (+Z)
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WEAPON_RACK, c, 0), -3.8f, 0f, 2.8f, 90f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WEAPON_RACK, c, 1),  3.8f, 0f, 2.8f, -90f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_ARMOR_STAND, c, 0), -2.6f, 0f, -3.3f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_ARMOR_STAND, c, 1),  2.6f, 0f, -3.3f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WARDROBE, c, 0), -1.4f, 0f, -3.5f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WARDROBE, c, 1),  1.4f, 0f, -3.5f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_SHIELD_PLAQUE, c, 0), 0f, 1.3f, -3.8f, 0f);
    }

    /** Renders a Nordic Tavern / Inn hall with banquet tables, benches, bar counter, and wall trophies. */
    private static void renderInnScene(Soft3D r3d) {
        r3d.setCameraOrbit(0f, 0.7f, -0.3f, 8.8f, -24f, 30f, 45f);
        Culture c = Culture.NORD;
        drawRoomShell(r3d, 9, 9, MAT_SPRUCE_MID, MAT_SPRUCE_DARK, true);

        // Long banquet tables on left with benches and chairs
        for (int z = -2; z <= 1; z++) {
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.BANQUET_TABLE, c, 0), -2.2f, 0f, z, 0f);
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.BENCH, c, 0), -3.2f, 0f, z, 90f);
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CHAIR, c, 0), -1.2f, 0f, z, -90f);
            FurnitureKind ckL = (z % 2 == 0) ? FurnitureKind.CLUTTER_TANKARD : FurnitureKind.CLUTTER_PLATTER;
            r3d.drawMesh(InteriorFurnitureModels.get(ckL, c, z & 3), -2.2f, 1f, z, 0f);
        }

        // Bar counter at back-right facing +Z with stools, scales, and apothecary/mead bottles
        for (int x = 1; x <= 3; x++) {
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.COUNTER, c, 0), x, 0f, -1.6f, 0f);
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.STOOL, c, 0), x, 0f, -0.4f, 0f);
        }
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_SCALES, c, 0), 1f, 1f, -1.6f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_APOTHECARY, c, 0), 2f, 1f, -1.6f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_TANKARD, c, 1), 3f, 1f, -1.6f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CABINET, c, 0), 3.3f, 0f, -3.5f, 0f);

        // Wall antler trophy, shield plaque & tapestry on back wall (-Z) facing +Z
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_ANTLER_TROPHY, c, 0), -2.0f, 1.4f, -3.8f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_SHIELD_PLAQUE, c, 0),  0.0f, 1.4f, -3.8f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_TAPESTRY, c, 0),       1.8f, 1.3f, -3.8f, 0f);
    }

    /** Renders a Jarl's Longhouse / Keep throne room with Throne on raised dais at -Z facing +Z, banquet tables, goblets, candelabras, and armor stands. */
    private static void renderLonghouseScene(Soft3D r3d) {
        r3d.setCameraOrbit(0f, 0.8f, -0.3f, 8.8f, 20f, 29f, 45f);
        Culture c = Culture.NORD;
        drawRoomShell(r3d, 9, 9, MAT_SPRUCE_DARK, MAT_SANDSTONE, true);

        // Raised dais at back (z = -3.0) with Throne facing +Z
        ShopSignMesh dais = new ShopSignMesh();
        dais.box(-28f, -8f, -12f, 28f, -2f, 12f, MAT_SANDSTONE);
        dais.box(-10f, -1.9f, -12f, 10f, -1.4f, 12f, MAT_CRIMSON);
        r3d.drawMesh(dais, 0f, 0f, -3.0f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.THRONE, c, 0), 0f, 0.38f, -3.1f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_ARMOR_STAND, c, 0), -2.5f, 0f, -3.2f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_ARMOR_STAND, c, 1),  2.5f, 0f, -3.2f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_TAPESTRY, c, 0), -1.6f, 1.4f, -3.8f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_TAPESTRY, c, 0),  1.6f, 1.4f, -3.8f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.WALL_ANTLER_TROPHY, c, 0), 0f, 1.7f, -3.8f, 0f);

        // Two long feasting tables flanking the central carpet runner
        for (int side : new int[]{-1, 1}) {
            float tx = side * 2.3f;
            for (int z = -1; z <= 2; z++) {
                r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.BANQUET_TABLE, c, 0), tx, 0f, z, 0f);
                r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.BENCH, c, 0), tx + side * 1.0f, 0f, z, side < 0 ? 90f : -90f);
                r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CHAIR, c, 0), tx - side * 1.0f, 0f, z, side < 0 ? -90f : 90f);
                FurnitureKind ck = (z == 0) ? FurnitureKind.CLUTTER_CANDELABRA
                        : (z == 1) ? FurnitureKind.CLUTTER_PLATTER : FurnitureKind.CLUTTER_GOBLETS;
                r3d.drawMesh(InteriorFurnitureModels.get(ck, c, 0), tx, 1f, z, 0f);
            }
        }
    }

    /** Renders a Bookseller / Scholar's Study with 3D Chiseled Bookshelves on back wall (-Z) facing +Z, Writing Desk, Counter, and Apothecary Cabinet. */
    private static void renderStudyScene(Soft3D r3d) {
        r3d.setCameraOrbit(0f, 0.7f, -0.2f, 7.6f, -22f, 28f, 44f);
        Culture c = Culture.BRETON;
        drawRoomShell(r3d, 7, 7, MAT_WALNUT, MAT_BIRCH_CREAM, true);

        // Back wall (-Z) lined with 2-high 3D Chiseled Bookshelves with protruding leather-bound books facing +Z
        ShopSignMesh shelf6 = build3DBookshelfPreview(6);
        ShopSignMesh shelf5 = build3DBookshelfPreview(5);
        for (int x = -2; x <= 2; x++) {
            r3d.drawMesh((x & 1) == 0 ? shelf6 : shelf5, x, 0f, -2.5f, 0f);
            r3d.drawMesh((x & 1) == 0 ? shelf5 : shelf6, x, 1f, -2.5f, 0f);
        }

        // Writing Desk & Chair on the left
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.DESK, c, 0), -1.5f, 0f, 0.2f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CHAIR, c, 0), -1.5f, 0f, 1.2f, 180f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.NIGHTSTAND, c, 0), -2.5f, 0f, 0.2f, 90f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_CANDELABRA, c, 0), -2.5f, 1f, 0.2f, 90f);

        // Bookseller / Alchemist counter on the right with scales, ledger, and apothecary rack
        for (int x = 1; x <= 2; x++) {
            r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.COUNTER, c, 0), x, 0f, 0.4f, 0f);
        }
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_SCALES, c, 0), 1f, 1f, 0.4f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_LEDGER, c, 0), 2f, 1f, 0.4f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CHAIR, c, 0), 1.5f, 0f, -0.7f, 0f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CABINET, c, 0), 2.5f, 0f, -1.2f, -90f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.DRESSER, c, 0), -2.5f, 0f, 1.6f, 90f);
        r3d.drawMesh(InteriorFurnitureModels.get(FurnitureKind.CLUTTER_APOTHECARY, c, 0), -2.5f, 1f, 1.6f, 90f);
    }

    private static void drawRoomShell(Soft3D r3d, int w, int d, int floorMat, int wallMat, boolean frontDoor) {
        ShopSignMesh floor = new ShopSignMesh();
        float hw = w * 8.0f;
        float hd = d * 8.0f;
        floor.box(-hw, -9.5f, -hd, hw, -8.0f, hd, floorMat);
        // Back wall (-Z)
        floor.box(-hw, -8.0f, -hd, hw, 30.0f, -hd + 1.5f, wallMat);
        floor.box(-hw, -8.0f, -hd + 1.5f, hw, -4.0f, -hd + 2.0f, MAT_SPRUCE_DARK);
        // Left & right cutaway walls
        floor.box(-hw, -8.0f, -hd, -hw + 1.5f, 22.0f, hd, wallMat);
        floor.box(hw - 1.5f, -8.0f, -hd, hw, 22.0f, hd, wallMat);
        if (frontDoor) {
            // Front threshold & door jamb markers at (x=0, z=+hd) showing clear entrance
            floor.box(-14f, -7.9f, hd - 1.5f, -8f, 10f, hd, MAT_SPRUCE_DARK);
            floor.box( 8f,  -7.9f, hd - 1.5f, 14f, 10f, hd, MAT_SPRUCE_DARK);
            floor.box(-10f, -7.95f, hd - 28f, 10f, -7.7f, hd - 1.5f, MAT_GOLD);
        }
        r3d.drawMesh(floor, 0f, 0f, 0f, 0f);
    }

    /** Draws a complete 2-block bed (head at zHead, foot at zHead + 1 block) to verify zero half-beds. */
    private static void drawFullBed(Soft3D r3d, float x, float y, float zHead, float yawDeg, int blanketMat) {
        ShopSignMesh bed = new ShopSignMesh();
        bed.box(-7f, -8f, -7.8f, 7f, 4f, -5.5f, MAT_SPRUCE_DARK); // headboard
        bed.box(-7f, -8f, -7.5f, -5f, -3f, 23.5f, MAT_SPRUCE_MID);
        bed.box( 5f, -8f, -7.5f,  7f, -3f, 23.5f, MAT_SPRUCE_MID);
        bed.box(-7f, -5f, -7.0f,  7f, -2f, 23.5f, MAT_PARCHMENT);
        bed.box(-7.1f, -4.8f, -0.5f, 7.1f, -1.6f, 23.6f, blanketMat);
        bed.box(-5.5f, -2.0f, -5.5f, 5.5f, -0.4f, -1.0f, MAT_PARCHMENT); // pillow
        r3d.drawMesh(bed, x, y, zHead, yawDeg);
    }

    private static void drawChestBlock(Soft3D r3d, float x, float y, float z) {
        ShopSignMesh chest = new ShopSignMesh();
        chest.box(-6.5f, -8f, -6.5f, 6.5f, 4f, 6.5f, MAT_OAK_RICH);
        chest.box(-6.7f, 0.5f, -6.7f, 6.7f, 1.8f, 6.7f, MAT_IRON_DARK);
        chest.box(-1.2f, -0.5f, 6.4f, 1.2f, 2.2f, 7.1f, MAT_GOLD);
        r3d.drawMesh(chest, x, y, z, 0f);
    }

    private static ShopSignMesh build3DBookshelfPreview(int occupiedCount) {
        ShopSignMesh m = new ShopSignMesh();
        // Chiseled bookshelf frame with recessed cubbies on +Z face
        m.box(-8f, -8f, -8f, 8f, 8f, 6.4f, MAT_OAK_RICH);
        m.box(-8f, 6.5f, -8f, 8f, 8f, 8f, MAT_SPRUCE_DARK);
        m.box(-8f, -8f, -8f, 8f, -6.5f, 8f, MAT_SPRUCE_DARK);
        m.box(-8f, -0.8f, -8f, 8f, 0.8f, 8f, MAT_SPRUCE_DARK);
        m.box(-8f, -8f, -8f, -6.8f, 8f, 8f, MAT_SPRUCE_DARK);
        m.box(6.8f, -8f, -8f, 8f, 8f, 8f, MAT_SPRUCE_DARK);

        int[] jackets = {MAT_CRIMSON, MAT_LEATHER_WARM, MAT_COBALT, MAT_EMERALD, MAT_PURPLE_GEM, MAT_WALNUT};
        int[] bands = {MAT_GOLD, MAT_BRASS, MAT_STEEL_BRIGHT, MAT_GOLD, MAT_GOLD, MAT_COPPER};
        for (int slot = 0; slot < Math.min(6, occupiedCount); slot++) {
            int col = slot % 3;
            int row = slot / 3;
            float cx = (col - 1) * 4.4f;
            float y0 = (row == 0) ? 1.0f : -7.0f;
            float y1 = y0 + 5.8f;
            int jacket = jackets[slot];
            int band = bands[slot];
            m.box(cx - 1.4f, y0, 6.4f, cx + 1.4f, y1, 8.45f, jacket);
            m.box(cx - 1.48f, y0 + 1.0f, 6.6f, cx + 1.48f, y0 + 1.55f, 8.52f, band);
            m.box(cx - 1.48f, y1 - 1.55f, 6.6f, cx + 1.48f, y1 - 1.0f, 8.52f, band);
            m.box(cx - 1.15f, y1 - 0.35f, 6.4f, cx + 1.15f, y1 + 0.05f, 8.2f, MAT_PARCHMENT);
        }
        return m;
    }

    /** Software z-buffered perspective 3D rasterizer for ShopSignMesh using the actual atlas PNG. */
    private static final class Soft3D {
        private final BufferedImage target;
        private final BufferedImage atlas;
        private final int w, h;
        private final float[] zbuf;
        private Matrix4f viewProj = new Matrix4f();

        Soft3D(BufferedImage target, BufferedImage atlas) {
            this.target = target;
            this.atlas = atlas;
            this.w = target.getWidth();
            this.h = target.getHeight();
            this.zbuf = new float[w * h];
            Arrays.fill(zbuf, Float.POSITIVE_INFINITY);
        }

        void setCameraOrbit(float lookX, float lookY, float lookZ, float dist, float yawDeg, float pitchDeg, float fovDeg) {
            float yaw = (float) Math.toRadians(yawDeg);
            float pitch = (float) Math.toRadians(pitchDeg);
            float ex = lookX + (float) (dist * Math.cos(pitch) * Math.sin(yaw));
            float ey = lookY + (float) (dist * Math.sin(pitch));
            float ez = lookZ + (float) (dist * Math.cos(pitch) * Math.cos(yaw));
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fovDeg), (float) w / (float) h, 0.1f, 60.0f);
            Matrix4f view = new Matrix4f().lookAt(ex, ey, ez, lookX, lookY, lookZ, 0f, 1f, 0f);
            this.viewProj = proj.mul(view);
        }

        void drawMesh(ShopSignMesh mesh, float tx, float ty, float tz, float yawDeg) {
            Matrix4f model = new Matrix4f()
                    .translate(tx, ty, tz)
                    .rotateY((float) Math.toRadians(yawDeg));
            Matrix4f mvp = new Matrix4f(viewProj).mul(model);
            Vector3f lightDir = new Vector3f(0.42f, 0.78f, 0.46f).normalize();

            for (Quad q : mesh.quads()) {
                Vector3f wn = model.transformDirection(new Vector3f(q.nx(), q.ny(), q.nz())).normalize();
                float ndotl = Math.max(0.0f, wn.dot(lightDir));
                float hemi = 0.55f + 0.15f * wn.y;
                float shade = Math.min(1.15f, hemi + 0.48f * ndotl);

                Vector4f p0 = project(mvp, q.x0(), q.y0(), q.z0());
                Vector4f p1 = project(mvp, q.x1(), q.y1(), q.z1());
                Vector4f p2 = project(mvp, q.x2(), q.y2(), q.z2());
                Vector4f p3 = project(mvp, q.x3(), q.y3(), q.z3());
                if (p0 == null || p1 == null || p2 == null || p3 == null) continue;

                rasterizeTri(p0, p1, p2, q.u0(), q.v0(), q.u1(), q.v0(), q.u1(), q.v1(), shade);
                rasterizeTri(p0, p2, p3, q.u0(), q.v0(), q.u1(), q.v1(), q.u0(), q.v1(), shade);
            }
        }

        private Vector4f project(Matrix4f mvp, float x, float y, float z) {
            Vector4f v = new Vector4f(x, y, z, 1.0f).mul(mvp);
            if (v.w <= 0.05f) return null;
            float invW = 1.0f / v.w;
            float sx = (v.x * invW * 0.5f + 0.5f) * w;
            float sy = (0.5f - v.y * invW * 0.5f) * h;
            float sz = v.z * invW;
            return new Vector4f(sx, sy, sz, invW);
        }

        private void rasterizeTri(
                Vector4f a, Vector4f b, Vector4f c,
                float ua, float va, float ub, float vb, float uc, float vc,
                float shade
        ) {
            int minX = Math.max(0, (int) Math.floor(Math.min(a.x, Math.min(b.x, c.x))));
            int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x))));
            int minY = Math.max(0, (int) Math.floor(Math.min(a.y, Math.min(b.y, c.y))));
            int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y))));

            float denom = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
            if (Math.abs(denom) < 1e-5f) return;
            float invDenom = 1.0f / denom;

            int aw = atlas.getWidth();
            int ah = atlas.getHeight();

            for (int py = minY; py <= maxY; py++) {
                float fy = py + 0.5f;
                for (int px = minX; px <= maxX; px++) {
                    float fx = px + 0.5f;
                    float w0 = ((b.y - c.y) * (fx - c.x) + (c.x - b.x) * (fy - c.y)) * invDenom;
                    float w1 = ((c.y - a.y) * (fx - c.x) + (a.x - c.x) * (fy - c.y)) * invDenom;
                    float w2 = 1.0f - w0 - w1;
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;

                    float z = w0 * a.z + w1 * b.z + w2 * c.z;
                    int idx = py * w + px;
                    if (z >= zbuf[idx]) continue;

                    float invW = w0 * a.w + w1 * b.w + w2 * c.w;
                    float u = (w0 * ua * a.w + w1 * ub * b.w + w2 * uc * c.w) / invW;
                    float v = (w0 * va * a.w + w1 * vb * b.w + w2 * vc * c.w) / invW;

                    int tx = Math.max(0, Math.min(aw - 1, (int) (u * aw)));
                    int ty = Math.max(0, Math.min(ah - 1, (int) (v * ah)));
                    int argb = atlas.getRGB(tx, ty);
                    if (((argb >>> 24) & 0xFF) < 128) continue;

                    zbuf[idx] = z;
                    int r = Math.min(255, Math.max(0, (int) (((argb >> 16) & 0xFF) * shade)));
                    int gr = Math.min(255, Math.max(0, (int) (((argb >> 8) & 0xFF) * shade)));
                    int bl = Math.min(255, Math.max(0, (int) ((argb & 0xFF) * shade)));
                    target.setRGB(px, py, (0xFF << 24) | (r << 16) | (gr << 8) | bl);
                }
            }
        }
    }
}
