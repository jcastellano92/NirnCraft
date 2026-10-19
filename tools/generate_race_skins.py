import os
from PIL import Image, ImageDraw

OUTPUT_DIR = "mod/src/main/resources/assets/nirncraft/textures/entity/race"
os.makedirs(OUTPUT_DIR, exist_ok=True)

def shade(color, factor):
    return (
        max(0, min(255, int(color[0] * factor))),
        max(0, min(255, int(color[1] * factor))),
        max(0, min(255, int(color[2] * factor))),
        color[3] if len(color) > 3 else 255
    )

def fill_rect(draw, x1, y1, x2, y2, color):
    draw.rectangle([x1, y1, x2, y2], fill=color)

def generate_skin(name, skin_c, hair_c, eye_c, cloth_c, cloth2_c, accent_c, features=None):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # ---------------- 1. HEAD BASE (0..32, 0..16) ----------------
    # Top & Bottom of head
    fill_rect(draw, 8, 0, 15, 7, hair_c)        # Top head
    fill_rect(draw, 16, 0, 23, 7, shade(skin_c, 0.85)) # Bottom head (chin)
    
    # Sides, Front, Back
    fill_rect(draw, 0, 8, 7, 15, hair_c)        # Right head
    fill_rect(draw, 8, 8, 15, 15, skin_c)       # Front face
    fill_rect(draw, 16, 8, 23, 15, hair_c)      # Left head
    fill_rect(draw, 24, 8, 31, 15, hair_c)      # Back head
    
    # Hair on front forehead (y = 8, 9)
    fill_rect(draw, 8, 8, 15, 9, hair_c)
    draw.point((8, 10), fill=hair_c)
    draw.point((15, 10), fill=hair_c)
    
    # Eyes on front face (x: 9, 10 and 13, 14; y: 12)
    # Sclera / eye background
    draw.point((9, 12), fill=(240, 240, 240, 255) if features != 'dunmer' else (160, 40, 40, 255))
    draw.point((10, 12), fill=eye_c)
    draw.point((13, 12), fill=eye_c)
    draw.point((14, 12), fill=(240, 240, 240, 255) if features != 'dunmer' else (160, 40, 40, 255))
    
    # Eyebrows (y: 11)
    fill_rect(draw, 9, 11, 10, 11, shade(hair_c, 0.8))
    fill_rect(draw, 13, 11, 14, 11, shade(hair_c, 0.8))
    
    # Mouth (y: 14)
    fill_rect(draw, 11, 14, 12, 14, shade(skin_c, 0.7))
    
    # ---------------- 2. TORSO BASE (16..40, 16..32) ----------------
    # Torso top & bottom
    fill_rect(draw, 20, 16, 27, 19, shade(cloth_c, 0.9)) # Neck / shoulders
    fill_rect(draw, 28, 16, 35, 19, shade(cloth2_c, 0.85)) # Waist bottom
    
    # Torso Sides, Front, Back
    fill_rect(draw, 16, 20, 19, 31, shade(cloth_c, 0.85)) # Right side
    fill_rect(draw, 20, 20, 27, 31, cloth_c)              # Front chest
    fill_rect(draw, 28, 20, 31, 31, shade(cloth_c, 0.85)) # Left side
    fill_rect(draw, 32, 20, 39, 31, shade(cloth_c, 0.95)) # Back
    
    # Neck visible at collar (x: 23, 24; y: 20, 21)
    fill_rect(draw, 23, 20, 24, 21, skin_c)
    # Belt / sash at bottom of torso (y: 29..31)
    fill_rect(draw, 16, 29, 39, 31, cloth2_c)
    fill_rect(draw, 23, 29, 24, 30, accent_c) # Belt buckle
    
    # ---------------- 3. RIGHT ARM BASE (40..56, 16..32) ----------------
    fill_rect(draw, 44, 16, 47, 19, shade(cloth_c, 0.9)) # Shoulder top
    fill_rect(draw, 48, 16, 51, 19, skin_c)             # Hand bottom
    fill_rect(draw, 40, 20, 55, 27, cloth_c)            # Upper sleeve
    fill_rect(draw, 40, 28, 55, 31, skin_c)             # Forearm / hand
    
    # ---------------- 4. LEFT ARM BASE (32..48, 48..64) ----------------
    fill_rect(draw, 36, 48, 39, 51, shade(cloth_c, 0.9)) # Shoulder top
    fill_rect(draw, 40, 48, 43, 51, skin_c)             # Hand bottom
    fill_rect(draw, 32, 52, 47, 59, cloth_c)            # Upper sleeve
    fill_rect(draw, 32, 60, 47, 63, skin_c)             # Forearm / hand
    
    # ---------------- 5. RIGHT LEG BASE (0..16, 16..32) ----------------
    fill_rect(draw, 4, 16, 7, 19, cloth2_c)             # Leg top
    fill_rect(draw, 8, 16, 11, 19, shade(accent_c, 0.6)) # Boot sole
    fill_rect(draw, 0, 20, 15, 27, cloth2_c)            # Trousers
    fill_rect(draw, 0, 28, 15, 31, shade(accent_c, 0.8)) # Boots
    
    # ---------------- 6. LEFT LEG BASE (16..32, 48..64) ----------------
    fill_rect(draw, 20, 48, 23, 51, cloth2_c)           # Leg top
    fill_rect(draw, 24, 48, 27, 51, shade(accent_c, 0.6)) # Boot sole
    fill_rect(draw, 16, 52, 31, 59, cloth2_c)          # Trousers
    fill_rect(draw, 16, 60, 31, 63, shade(accent_c, 0.8)) # Boots
    
    # ---------------- 7. OUTER 3D OVERLAYS (Hat, Jacket, Details) ----------------
    # Head outer layer (32..64, 0..16)
    # Hair strands & depth
    fill_rect(draw, 40, 0, 47, 7, shade(hair_c, 1.05)) # Top outer
    fill_rect(draw, 32, 8, 39, 15, hair_c)             # Right outer hair
    fill_rect(draw, 48, 8, 55, 15, hair_c)             # Left outer hair
    fill_rect(draw, 56, 8, 63, 15, hair_c)             # Back outer hair
    fill_rect(draw, 40, 8, 47, 9, shade(hair_c, 1.1))  # Front outer bangs
    
    # Race-specific unique features on outer layer
    if features == 'nord':
        # Blue woad warpaint streak over right eye (x: 42; y: 10..13)
        draw.line([(42, 10), (42, 14)], fill=(45, 95, 165, 240))
        draw.point((41, 12), fill=(45, 95, 165, 200))
        # Fur collar around neck (outer torso 20..27, 36..38)
        fill_rect(draw, 20, 36, 27, 37, (180, 165, 140, 255))
    elif features == 'imperial':
        # Laurel / bronze circlet accent on forehead (outer head 40..47, 9)
        fill_rect(draw, 40, 9, 47, 9, (195, 150, 60, 240))
        # Roman pteruges / leather shoulder guard
        fill_rect(draw, 44, 36, 47, 38, (140, 30, 30, 230))
    elif features == 'breton':
        # Silver embroidered collar trim
        fill_rect(draw, 21, 36, 26, 37, (190, 200, 215, 230))
    elif features == 'redguard':
        # Desert headwrap / keffiyeh band
        fill_rect(draw, 32, 8, 63, 8, (215, 205, 190, 255))
        fill_rect(draw, 44, 37, 47, 40, (190, 140, 45, 230)) # Gold armband
    elif features == 'altmer':
        # Sharp high-elven collar & golden filigree embroidery
        fill_rect(draw, 20, 36, 27, 40, (35, 35, 65, 180))
        draw.line([(21, 36), (23, 44)], fill=(225, 190, 70, 255))
        draw.line([(26, 36), (24, 44)], fill=(225, 190, 70, 255))
    elif features == 'bosmer':
        # Woodland leather browband with feather/leaf
        fill_rect(draw, 40, 9, 47, 9, (85, 110, 60, 255))
        draw.point((47, 8), fill=(160, 180, 80, 255))
        # Quiver harness strap diagonally across chest
        for d in range(8):
            draw.point((20 + d, 36 + d), fill=(70, 50, 30, 230))
    elif features == 'dunmer':
        # Ashlander ritual marks on brow (crimson tattoo pips)
        draw.point((42, 9), fill=(185, 30, 30, 240))
        draw.point((45, 9), fill=(185, 30, 30, 240))
        # Dark chitin chest carapace trim
        fill_rect(draw, 21, 37, 26, 40, (65, 25, 30, 220))
    elif features == 'orsimer':
        # Lower ivory tusks protruding upward on mouth sides (x: 41, 46; y: 14)
        draw.point((41, 14), fill=(235, 235, 205, 255))
        draw.point((41, 13), fill=(235, 235, 205, 255))
        draw.point((46, 14), fill=(235, 235, 205, 255))
        draw.point((46, 13), fill=(235, 235, 205, 255))
        # Heavy studded leather & steel bands
        fill_rect(draw, 20, 37, 27, 38, (150, 150, 155, 230))
    elif features == 'khajiit':
        # Cat ears protruding on top-sides (outer head 34..35 and 44..45 on top y: 0..2)
        fill_rect(draw, 41, 7, 42, 8, (135, 95, 55, 255)) # Right ear
        fill_rect(draw, 45, 7, 46, 8, (135, 95, 55, 255)) # Left ear
        # Whiskers on cheeks
        draw.point((39, 13), fill=(230, 225, 210, 220))
        draw.point((48, 13), fill=(230, 225, 210, 220))
    elif features == 'argonian':
        # Scaled brow ridges and aquatic head frills / horns
        draw.line([(40, 7), (40, 9)], fill=(120, 85, 55, 255))
        draw.line([(47, 7), (47, 9)], fill=(120, 85, 55, 255))
        # Mottled reptilian scales texture highlights
        for (sx, sy) in [(21, 23), (25, 25), (23, 27)]:
            draw.point((sx, sy), fill=(95, 155, 125, 255))
            
    out_path = os.path.join(OUTPUT_DIR, f"{name}.png")
    img.save(out_path)
    print(f"Generated {out_path} ({img.size[0]}x{img.size[1]})")

# 10 Playable Elder Scrolls Races
skins = [
    # name, skin, hair, eyes, cloth, cloth2, accent, features
    ("nord", (226, 196, 175), (205, 178, 115), (75, 135, 185), (72, 76, 82), (55, 45, 38), (175, 140, 85), "nord"),
    ("imperial", (212, 178, 145), (48, 38, 32), (80, 52, 32), (145, 38, 38), (62, 52, 45), (195, 150, 60), "imperial"),
    ("breton", (228, 204, 186), (92, 62, 42), (115, 105, 65), (48, 68, 112), (58, 48, 40), (180, 190, 205), "breton"),
    ("redguard", (132, 90, 58), (26, 22, 22), (150, 110, 48), (192, 186, 175), (78, 48, 32), (185, 135, 40), "redguard"),
    ("altmer", (228, 196, 120), (235, 226, 188), (218, 165, 32), (32, 32, 58), (48, 42, 55), (225, 190, 70), "altmer"),
    ("bosmer", (196, 162, 128), (122, 58, 32), (42, 122, 62), (76, 92, 56), (92, 75, 52), (135, 105, 65), "bosmer"),
    ("dunmer", (116, 124, 134), (22, 22, 26), (198, 32, 32), (86, 26, 32), (46, 46, 52), (175, 50, 50), "dunmer"),
    ("orsimer", (122, 142, 98), (32, 34, 30), (192, 142, 32), (66, 56, 46), (52, 45, 38), (155, 155, 160), "orsimer"),
    ("khajiit", (192, 146, 88), (122, 86, 46), (172, 198, 52), (168, 78, 48), (72, 60, 48), (42, 138, 138), "khajiit"),
    ("argonian", (68, 118, 98), (122, 88, 58), (212, 178, 38), (86, 76, 62), (65, 55, 45), (138, 105, 65), "argonian")
]

for args in skins:
    generate_skin(*args)

print("All 10 Skyrim race skins successfully created!")
