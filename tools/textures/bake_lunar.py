"""Render smooth lunar phases from public NASA LROC albedo and LOLA topography.

Source: NASA Scientific Visualization Studio, CGI Moon Kit, https://svs.gsfc.nasa.gov/4720/
Data is public domain: https://svs.gsfc.nasa.gov/help/#frequently-asked-questions
These are Moon-derived art surfaces, recoloured/reoriented for the fictional bodies;
they are not Bethesda textures or claims of canonical Masser/Secunda topography.
"""
from pathlib import Path
import json
import urllib.request
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "dist/.cache/lunar"
DEST = ROOT / "mod/src/main/resources/assets/nirncraft/textures/environment"
BASE = "https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/"


def fetch(name):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / name
    if not path.exists():
        urllib.request.urlretrieve(BASE + name, path)
    return path


def bilinear(image, u, v):
    height, width = image.shape[:2]
    x, y = (u % 1) * width - 0.5, np.clip(v, 0, 1) * (height - 1)
    ix, iy = np.floor(x).astype(int), np.floor(y).astype(int)
    fx, fy = x - ix, y - iy
    if image.ndim == 3:
        fx, fy = fx[..., None], fy[..., None]
    a = image[iy.clip(0, height-1), ix % width]
    b = image[iy.clip(0, height-1), (ix+1) % width]
    c = image[(iy+1).clip(0, height-1), ix % width]
    d = image[(iy+1).clip(0, height-1), (ix+1) % width]
    return (a*(1-fx)+b*fx)*(1-fy) + (c*(1-fx)+d*fx)*fy


def render(albedo, elevation, size, longitude, tint):
    y, x = (np.mgrid[:size, :size] + 0.5 - size/2) / (size * 0.46)
    radius2 = x*x + y*y
    z = np.sqrt(np.maximum(0, 1-radius2))
    def height_at(px):
        pz = np.sqrt(np.maximum(0, 1-px*px-y*y))
        u = np.arctan2(px, pz) / (2*np.pi) + 0.5 + longitude
        v = 0.5 + np.arcsin(np.clip(y, -1, 1)) / np.pi
        return bilinear(elevation, u, v)
    u = np.arctan2(x, z) / (2*np.pi) + 0.5 + longitude
    v = 0.5 + np.arcsin(np.clip(y, -1, 1)) / np.pi
    color = bilinear(albedo, u, v) * np.array(tint)
    h = height_at(x)
    gy, gx = np.gradient(h, 1/(size*0.46))
    nx, ny, nz = x-gx*z, y-gy*z, z
    length = np.maximum(0.001, np.sqrt(nx*nx+ny*ny+nz*nz))
    nx, ny, nz = nx/length, ny/length, nz/length
    alpha = np.clip((1-np.sqrt(radius2))*size*0.46, 0, 1)
    atlas = np.zeros((size*4, size*4, 4), np.uint8)
    for phase in range(16):
        angle = phase * np.pi/8
        lx, lz = np.sin(angle), np.cos(angle)
        diffuse = np.maximum(0, nx*lx+nz*lz)
        light = diffuse * 0.75 + 0.25 * diffuse / np.maximum(0.02, diffuse+nz)
        for step in (0.001, 0.0025, 0.006, 0.014, 0.03):
            px = x + lx*step
            terrain = np.sqrt(np.maximum(0, 1-px*px-y*y)) + height_at(px)
            delta = terrain - (z+h+lz*step)
            shadow = np.clip((delta-0.00002)/0.00018, 0, 1)
            shadow *= px*px+y*y < 1
            light *= 1-shadow*0.92
        # Linear-light diffuse shading, converted to sRGB only for texture storage.
        rgb = color * light[..., None] * 0.85 + np.array([0.0003,0.0004,0.0006])
        rgba = np.empty((size,size,4),np.uint8)
        rgba[...,:3] = np.uint8(np.clip(rgb,0,1)**(1/2.2)*255+0.5)
        rgba[...,3] = np.uint8(alpha*255+0.5)
        row,col = divmod(phase,4)
        atlas[row*size:(row+1)*size,col*size:(col+1)*size] = rgba
    return atlas


if __name__ == "__main__":
    albedo = (np.asarray(Image.open(fetch("lroc_color_poles_4k.tif")).convert("RGB"),dtype=float)/255)**2.2
    # Half-metre unsigned height relative to a radius 10 km below the reference sphere.
    elevation = (np.asarray(Image.open(fetch("ldem_16_uint.tif")),dtype=float)*0.5 - 10000)/1737400
    for name,size,longitude,tint in (("masser_phases",512,0.28,(1.0,0.78,0.97)),
                                     ("secunda_phases",256,0.0,(0.91,0.96,1.0))):
        path = DEST / (name+".png")
        Image.fromarray(render(albedo,elevation,size,longitude,tint),"RGBA").save(path)
        path.with_name(path.name+".mcmeta").write_text(json.dumps({"texture":{"blur":True,"clamp":True}}))
        print("Rendered",path)
    (DEST/"lunar_sources.txt").write_text(__doc__ + "\nCredit: NASA's Scientific Visualization Studio. Ernie Wright; LRO LROC and LOLA instrument teams.\n",encoding="utf-8")
