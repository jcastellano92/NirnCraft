# Nirn's sky: what the sky shader represents

The owner's brief for the unified sky (atlas_sky / the night shader, AtlasSky, NightSky, NightSkyMath). Everything is
**rendered by our own physically based system** (the air's transmittance and haze, the eye's adaptation, real lighting
geometry): realistic in how it works and is lit, a little fantastical in what is up there. Nothing is copied from any
game's assets or screenshots; the references below are for mood and lore facts only.

Everything changes with **where you are** (latitude and longitude across Tamriel: what is above the horizon, how high,
how the aurora fades going south) and **when** (time of night, day of year, the moons' months, the planets' periods).

## The moons

- **Masser** (Jode, Mara's Tear): the greater moon, well over twice Secunda's size. A dead moon whose "skin" withered:
  reddish, rose to rust, mottled, scarred. Large on the sky (NightSkyMath.MASSER_RADIUS, about 21 degrees across).
- **Secunda** (Jone, Shandar's Sorrow): the lesser, greyish-white, also dead and withered, cratered. About 7 degrees
  across: much smaller than Masser, still far larger than Earth's Moon.
- Both: fully custom baked surfaces (albedo + height, no likeness of Earth's Moon), lit by the real sun direction
  (phases from geometry), relief with cast shadows near the terminator, under the air's transmittance and haze.
- Each moon's light lights the land in its own colour (the brighter is the key light, the other a fill).
- **Eclipses**: the moons can pass in front of the sun several times a year ("Vampire Days"): real occultation geometry,
  the land darkening, the corona and a ring of light round the occulting disc.
- **The Dark Moon** (the Hidden or Ghost Moon, Lorkhaj's corpse): seen only in a **dark eclipse**, when Masser and
  Secunda both cover the sun: a third, dark disc revealed by the corona's rim around it. Rare.
- **Necromancer's Moon** (the Revenant): a small dark body that eclipses the planet Arkay every eight days, Arkay's light
  hidden for 24 hours ("Shade of the Revenant": a faint purple-red cast). Era-dependent (exists from 3E 417): behind a
  config switch, off by default.

## Stars

- Countless procedural stars (magnitudes, colours by temperature), sidereal rotation, twinkling by the air's seeing.
- **Constellations are represented by star placement only, no drawn lines**: the 13 birthsigns (the Warrior, Mage,
  Thief and their charges: Lady, Steed, Lord, Apprentice, Atronach, Ritual, Lover, Shadow, Tower; and the Serpent, which
  wanders) as recognisable groups of brighter stars, with ordinary stars between them, never only them.
- **The planet Arkay marks the eye of the Thief; Julianos the eye of the Mage.**
- **Named stars**, each a real point of light at a fixed position: the Nine Coruscations, coloured: Iana-Lor (violet),
  Londa-Vera (green), Merid-Nunda (red), Mnemo-Li (blue), Prime Archon (white), Sheza-Rana (cyan), Unala-Se (yellow),
  Valia-Sha (orange), Xero-Lyg (black: a dark point that hides the stars behind it). Also Azura's Star, Darumzu and
  Harakk (favoured stars of the Atmoran sky: northern), the Northern Sisters, the Southron Pole Star, the Star of the
  East, the Three Stars of Locanda.
- The galaxy band stays visible in a dark sky.
- Stars are tears into Aetherius: a slightly richer, more saturated sparkle than Earth's sky, still physical in
  brightness (fading with sky brightness, the eye's adaptation, the moons' glare).

## Planets

The Dominion planets, seen as bright wandering points (some showing a tiny disc and phase at the brightest), on their
own orbits about the sun, so they appear at the right times of year and places in the sky:

- **Akatosh**: no satellites.
- **Arkay**: no satellites; the eye of the Thief at the right season.
- **Julianos**, with its satellite **Stendarr**; the eye of the Mage at the right season.
- **Kynareth**: no satellites.
- **Zenithar**, with its satellite **Mara**, which has its own satellite **Dibella** (a nested system: Mara circles
  Zenithar, Dibella circles Mara).

Consistent, deterministic ephemerides (periods, inclinations, phases) seeded once, so a given date and place always
shows the same sky.

## Comets, meteors and other events

- **Meteors** (falling stars, Aetherial fragments): sporadic ones, showers on configured nights, rare fireballs that
  light the land (in the sky already).
- **Comets**: a bright coma and a long curved tail pointing away from the sun (dust tail curved, ion tail straight
  and blue-violet), on long orbits: visible for weeks on rare years, growing and fading.
- **The aurora**: a volume over the north (in the sky already).

## Lighting

Every body that is bright enough lights the world through the same system: the sun, both moons (phase, colour), a
strong aurora, fireballs, a bright comet, eclipses dimming everything; all through the air and the eye's exposure.
